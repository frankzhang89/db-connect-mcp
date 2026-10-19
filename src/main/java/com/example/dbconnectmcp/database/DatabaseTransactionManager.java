package com.example.dbconnectmcp.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retains one connection and data source lease for each explicit transaction.
 * IDs route requests; the MCP session is the independent ownership boundary.
 * No registry lock is held during JDBC I/O. Each entry's lock serializes SQL,
 * completion, expiry, and shutdown so cleanup cannot close an executing connection.
 */
public final class DatabaseTransactionManager implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseTransactionManager.class);
    private static final String TRUNCATE_WARNING = "TRUNCATE on this database may implicitly commit earlier writes "
            + "and cannot be rolled back. ROLLBACK only undoes work after the last implicit commit.";
    private final DatabaseRuntimeManager runtime;
    private final DatabaseTransactionProperties limits;
    private final TransactionSqlGuard guard;
    private final TransactionResultMapper mapper;
    private final int statementTimeout;
    private final int maxRows;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Transaction> transactions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> endedSessions = new ConcurrentHashMap<>();
    private final Semaphore capacity;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycle = new Object();
    private final ScheduledExecutorService reaper;

    public DatabaseTransactionManager(DatabaseRuntimeManager runtime, DatabaseTransactionProperties limits,
            int statementTimeout, int maxRows, int maxCellChars) {
        this(runtime, limits, statementTimeout, maxRows, maxCellChars, System::nanoTime);
    }

    // A monotonic test clock makes timeout tests deterministic without sleeping.
    DatabaseTransactionManager(DatabaseRuntimeManager runtime, DatabaseTransactionProperties limits,
            int statementTimeout, int maxRows, int maxCellChars, LongSupplier clock) {
        this.runtime = runtime;
        this.limits = limits;
        this.clock = clock;
        this.guard = new TransactionSqlGuard(limits);
        this.mapper = new TransactionResultMapper(maxRows, maxCellChars);
        this.statementTimeout = Math.max(1, statementTimeout);
        this.maxRows = Math.max(1, maxRows);
        this.capacity = new Semaphore(limits.maxActive());
        reaper = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("db-transaction-reaper").factory());
        reaper.scheduleWithFixedDelay(this::expireIdle, 1, 1, TimeUnit.SECONDS);
    }

    public Map<String, Object> execute(String sessionId, String sql, String transactionId) {
        if (sessionId == null || sessionId.isBlank()) { return error("SESSION_REQUIRED", "A stateful MCP session is required."); }
        if (closed.get()) { return error("SERVICE_CLOSED", "The transaction service is closed."); }
        if (endedSessions.containsKey(sessionId)) { return error("SESSION_CLOSED", "The MCP session has ended."); }
        final TransactionSqlGuard.Plan plan;
        try { plan = guard.validate(sql); }
        catch (IllegalArgumentException error) {
            // Rejected scripts have made no database calls and do not poison an active transaction.
            return error("SQL_REJECTED", error.getMessage());
        }
        if (plan.command() == TransactionSqlGuard.Command.BEGIN) {
            if (transactionId != null) { return error("NESTED_TRANSACTION", "BEGIN must not include a transactionId."); }
            return begin(sessionId);
        }
        if (transactionId == null || !transactionId.matches("tx-[a-f0-9-]{36}")) {
            return error("TRANSACTION_REQUIRED", "Pass the transactionId returned by BEGIN; no SQL was executed.");
        }
        Transaction tx = transactions.get(transactionId);
        if (tx == null || !tx.owner.equals(sessionId)) {
            // Do not reveal whether another session owns an otherwise valid identifier.
            return error("TRANSACTION_NOT_FOUND", "The transaction is unavailable, ended, expired, or belongs to another session.");
        }
        boolean locked = false;
        try {
            locked = tx.lock.tryLock(statementTimeout, TimeUnit.SECONDS);
            if (!locked) { return failure(tx, "TRANSACTION_BUSY", "Another operation is using this transaction."); }
            if (closed.get()) { return failure(tx, "SERVICE_CLOSED", "The transaction service is closed."); }
            if (transactions.get(tx.id) != tx) { return failure(tx, "TRANSACTION_ENDED", "The transaction has ended."); }
            if (endedSessions.containsKey(tx.owner)) {
                rollbackAndRelease(tx, "SESSION_CLOSED");
                return failure(tx, "SESSION_CLOSED", "The MCP session has ended; cleanup attempted rollback.");
            }
            if (expired(tx)) {
                rollbackAndRelease(tx, "EXPIRED");
                return failure(tx, "TRANSACTION_EXPIRED", "The transaction expired and cleanup attempted rollback.");
            }
            if (plan.command() == TransactionSqlGuard.Command.ROLLBACK) { return finish(tx, false); }
            if (tx.rollbackOnly) { return failure(tx, "ROLLBACK_REQUIRED", "A previous execution failed; explicitly ROLLBACK this transaction."); }
            if (plan.command() == TransactionSqlGuard.Command.COMMIT) { return finish(tx, true); }
            return run(tx, plan.statements());
        }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return failure(tx, "INTERRUPTED", "Interrupted while waiting for the transaction; no new SQL was executed.");
        }
        finally {
            if (locked) { tx.lastActivity = clock.getAsLong(); tx.lock.unlock(); }
        }
    }

    private Map<String, Object> begin(String owner) {
        if (!capacity.tryAcquire()) { return error("TRANSACTION_LIMIT", "The active transaction limit has been reached."); }
        DatabaseRuntimeManager.Lease lease = null;
        Connection connection = null;
        boolean published = false;
        try {
            // Pin the complete target before borrowing a connection. A later global switch
            // must not move this transaction or close its pool until the lease is released.
            lease = runtime.acquire();
            if (!lease.dialect().supportsExplicitTransactions()) {
                return error("TRANSACTIONS_UNSUPPORTED", "Explicit cross-call transactions are not supported by this database.");
            }
            if (!lease.reserveTransaction()) {
                return error("TRANSACTION_LIMIT", "This data source has reached its retained transaction capacity.");
            }
            connection = lease.connection();
            lease.dialect().beginTransaction(connection);
            Transaction tx = new Transaction(owner, lease, connection, clock.getAsLong());
            synchronized (lifecycle) {
                // Shutdown and publication share a short lock; JDBC setup never runs under it.
                if (closed.get()) { return error("SERVICE_CLOSED", "The transaction service is closed."); }
                if (endedSessions.containsKey(owner)) { return error("SESSION_CLOSED", "The MCP session has ended."); }
                transactions.put(tx.id, tx);
                published = true;
            }
            Map<String, Object> result = describe(tx, true);
            result.put("isolation", "READ_COMMITTED");
            result.put("truncateRollbackSupported", lease.dialect().transactionalTruncate());
            result.put("idleTimeoutSeconds", limits.idleTimeoutSeconds());
            result.put("maxLifetimeSeconds", limits.maxLifetimeSeconds());
            return result;
        }
        catch (SQLException | RuntimeException error) {
            return error("BEGIN_FAILED", "Could not start a transaction; check database support and pool capacity.");
        }
        finally {
            if (!published) {
                if (connection != null) {
                    // No user SQL ran, but failed setup can leave an uncertain session.
                    // Evict rather than risk returning partially configured state to the pool.
                    if (lease != null) { lease.evict(connection); }
                    dispose(connection);
                }
                if (lease != null) { lease.close(); }
                capacity.release();
            }
        }
    }

    private Map<String, Object> run(Transaction tx, List<TransactionSqlGuard.Sql> script) {
        List<Map<String, Object>> results = new ArrayList<>();
        long deadline = Math.min(tx.created + TimeUnit.SECONDS.toNanos(limits.maxLifetimeSeconds()),
                clock.getAsLong() + TimeUnit.SECONDS.toNanos(limits.scriptTimeoutSeconds()));
        int index = 0;
        try {
            for (; index < script.size(); index++) {
                long left = deadline - clock.getAsLong();
                if (left <= 0) { throw new SQLException("Script deadline exceeded.", "HYT00"); }
                var sql = script.get(index);
                // Mark before attempting TRUNCATE: Oracle can commit prior work even when
                // the DDL itself fails. Never claim full rollback after such an attempt.
                if (sql.truncate() && !tx.lease.dialect().transactionalTruncate()) { tx.implicitCommitPossible = true; }
                try (Statement statement = tx.connection.createStatement()) {
                    int remainingSeconds = (int) Math.max(1, TimeUnit.NANOSECONDS.toSeconds(left));
                    statement.setQueryTimeout(Math.min(statementTimeout, remainingSeconds));
                    // Do not use a row-limit setting on DML: some drivers can change the
                    // number of rows modified. Returned DML rows are bounded by the mapper.
                    if (sql.query()) { statement.setMaxRows(maxRows + 1); }
                    String text = sql.text();
                    if (sql.merge() && tx.lease.dialect() instanceof SqlServerDialect) { text += ";"; }
                    boolean rows = statement.execute(text);
                    int resultIndex = 0;
                    do {
                        long count = rows ? -1 : statement.getLargeUpdateCount();
                        if (!rows && count == -1) { break; }
                        if (resultIndex >= limits.maxStatements()) { throw new SQLException("Too many results.", "HY000"); }
                        Map<String, Object> item;
                        if (rows) {
                            try (ResultSet result = statement.getResultSet()) { item = new LinkedHashMap<>(mapper.read(result)); }
                        }
                        else {
                            item = new LinkedHashMap<>(Map.of("type", sql.truncate() ? "TRUNCATE" : "UPDATE", "affectedRows", count));
                        }
                        item.put("statementIndex", index);
                        item.put("resultIndex", resultIndex++);
                        results.add(item);
                        rows = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
                    } while (true);
                }
                if (clock.getAsLong() >= deadline) { throw new SQLException("Script deadline exceeded.", "HYT00"); }
            }
            Map<String, Object> result = describe(tx, true);
            result.put("results", results);
            return result;
        }
        catch (SQLException | RuntimeException error) {
            // Preserve the handle for explicit rollback, but never let a caller commit a
            // partially executed script or continue after a driver-specific abort.
            tx.rollbackOnly = true;
            Map<String, Object> result = failure(tx, "EXECUTION_FAILED", "SQL execution failed; stop and explicitly ROLLBACK. No automatic retry was performed.");
            result.put("statementIndex", Math.min(index, script.size() - 1));
            result.put("results", results);
            if (error instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().matches("[A-Z0-9]{5}")) {
                result.put("sqlState", sql.getSQLState());
            }
            return result;
        }
    }

    private Map<String, Object> finish(Transaction tx, boolean commit) {
        try {
            if (commit) { tx.connection.commit(); }
            else { tx.connection.rollback(); }
            tx.state = commit ? "COMMITTED" : "ROLLED_BACK";
            return describe(tx, true);
        }
        catch (SQLException | RuntimeException error) {
            // A lost commit acknowledgement is not proof of rollback. Remove the handle,
            // discard the physical connection, and explicitly report an unknown outcome.
            tx.state = "UNKNOWN";
            return failure(tx, commit ? "COMMIT_OUTCOME_UNKNOWN" : "ROLLBACK_OUTCOME_UNKNOWN",
                    "The transaction outcome could not be confirmed. Do not retry automatically; verify database state independently.");
        }
        finally { release(tx, tx.state.equals("UNKNOWN")); }
    }

    private Map<String, Object> describe(Transaction tx, boolean success) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("transactionId", tx.id);
        result.put("datasourceId", tx.lease.datasourceId());
        result.put("databaseType", tx.lease.dialect().name());
        result.put("state", tx.state.equals("ACTIVE") && tx.rollbackOnly ? "ROLLBACK_ONLY" : tx.state);
        result.put("implicitCommitPossible", tx.implicitCommitPossible);
        result.put("rollbackScope", tx.implicitCommitPossible ? "SINCE_LAST_IMPLICIT_COMMIT" : "FULL_TRANSACTION");
        if (tx.implicitCommitPossible) { result.put("warnings", List.of(TRUNCATE_WARNING)); }
        return result;
    }

    private Map<String, Object> failure(Transaction tx, String code, String message) {
        Map<String, Object> result = describe(tx, false);
        result.put("code", code);
        result.put("message", message);
        return result;
    }

    private Map<String, Object> error(String code, String message) {
        return Map.of("success", false, "code", code, "message", message);
    }

    int activeCount() { return transactions.size(); }

    private boolean expired(Transaction tx) {
        long now = clock.getAsLong();
        return now - tx.lastActivity >= TimeUnit.SECONDS.toNanos(limits.idleTimeoutSeconds())
                || now - tx.created >= TimeUnit.SECONDS.toNanos(limits.maxLifetimeSeconds());
    }

    void expireIdle() {
        for (Transaction tx : transactions.values()) {
            // An in-flight operation owns the connection. Expiry waits for a later pass
            // instead of closing resources under that operation or blocking the reaper.
            if (!tx.lock.tryLock()) { continue; }
            try {
                if (transactions.get(tx.id) == tx && expired(tx)) { rollbackAndRelease(tx, "EXPIRED"); }
            }
            finally { tx.lock.unlock(); }
        }
        // Short-lived tombstones prevent a BEGIN already in flight during session DELETE
        // from publishing afterward, without retaining every historical session forever.
        long now = clock.getAsLong();
        endedSessions.entrySet().removeIf(entry -> now - entry.getValue() > TimeUnit.SECONDS.toNanos(limits.maxLifetimeSeconds()));
    }

    /** Called only for an explicitly ended MCP session, never for a finished HTTP request. */
    public void closeSession(String sessionId) {
        synchronized (lifecycle) { endedSessions.put(sessionId, clock.getAsLong()); }
        for (Transaction tx : transactions.values()) {
            if (!tx.owner.equals(sessionId)) { continue; }
            tx.lock.lock();
            try {
                if (transactions.get(tx.id) == tx) { rollbackAndRelease(tx, "SESSION_CLOSED"); }
            }
            finally { tx.lock.unlock(); }
        }
    }

    private void rollbackAndRelease(Transaction tx, String state) {
        try { tx.connection.rollback(); tx.state = state; }
        catch (SQLException | RuntimeException error) { tx.state = "UNKNOWN"; }
        finally { release(tx, tx.state.equals("UNKNOWN")); }
    }

    private void release(Transaction tx, boolean discard) {
        if (!transactions.remove(tx.id, tx)) { return; }
        try {
            // A failed rollback/commit must not return a potentially dirty connection to
            // the pool. Eviction also prevents pool-close cleanup from implying success.
            try {
                if (discard) { tx.lease.evict(tx.connection); }
            }
            catch (RuntimeException error) { LOG.warn("Could not evict a transaction connection with unknown outcome"); }
            finally { dispose(tx.connection); }
        }
        finally { tx.lease.close(); capacity.release(); }
    }

    private void dispose(Connection connection) {
        try { connection.close(); }
        catch (SQLException | RuntimeException error) { LOG.warn("Could not close a transaction connection"); }
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            if (!closed.compareAndSet(false, true)) { return; }
        }
        reaper.shutdownNow();
        for (Transaction tx : transactions.values()) {
            tx.lock.lock();
            try {
                if (transactions.get(tx.id) == tx) { rollbackAndRelease(tx, "SERVICE_CLOSED"); }
            }
            finally { tx.lock.unlock(); }
        }
    }

    private static final class Transaction {
        final String id = "tx-" + UUID.randomUUID();
        final String owner;
        final DatabaseRuntimeManager.Lease lease;
        final Connection connection;
        final ReentrantLock lock = new ReentrantLock(true);
        final long created;
        volatile long lastActivity;
        volatile String state = "ACTIVE";
        volatile boolean rollbackOnly;
        volatile boolean implicitCommitPossible;

        Transaction(String owner, DatabaseRuntimeManager.Lease lease, Connection connection, long now) {
            this.owner = owner;
            this.lease = lease;
            this.connection = connection;
            this.created = now;
            this.lastActivity = now;
        }
    }
}
