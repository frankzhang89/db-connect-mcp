package com.example.dbconnectmcp.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.example.dbconnectmcp.database.DatabaseSwitchException.Code.*;

/**
 * Owns the process-wide JDBC target shared by all MCP clients and read/write tools.
 * A switch builds and validates a separate candidate before publishing it. Operations
 * that already hold a lease keep their original pool, dialect, and schema until done.
 * Retired pools are closed only after their last lease has been released.
 *
 * <p>Lock order, when both locks are needed, is switchLock followed by stateLock.
 * Normal operations use only stateLock; network validation and pool cleanup happen
 * outside that monitor so they do not hold up acquisition of other leases.
 */
public class DatabaseRuntimeManager implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseRuntimeManager.class);
    // Protects active, closed, pending validation, and all PoolState counters/flags.
    private final Object stateLock = new Object();
    // Serializes the entire switch, including validation; queries never take this lock.
    private final ReentrantLock switchLock = new ReentrantLock(true);
    private final DatabasePoolFactory factory;
    private final DatabaseSwitchProperties timeouts;
    private final ExecutorService validator = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("db-validation-", 0).factory());
    // Includes the active pool and retired pools still retained by existing leases.
    private final Set<PoolState> pools = new HashSet<>();
    private PoolState active;
    private boolean closed;
    // Tracks the unpublished candidate so shutdown can cancel validation and close it.
    private DatabaseTarget pending;
    private Future<DatabasePoolFactory.Namespace> pendingValidation;

    public DatabaseRuntimeManager(DatabasePoolFactory factory, DatabaseSwitchProperties timeouts) {
        this.factory = factory;
        this.timeouts = timeouts;
    }

    /**
     * Validates and publishes a new target without changing YAML or restarting MCP.
     * Before publication, any failure leaves the current target unchanged. The result
     * identifies both the newly published target and its immediate predecessor.
     */
    public Map<String, Object> switchTo(DatabaseConnectionSettings settings) {
        // One monotonic deadline covers waiting for another switch and validation.
        // Remaining time is checked again immediately before the target is published.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeouts.timeoutSeconds());
        boolean locked = false;
        DatabaseTarget candidate = null;
        Future<DatabasePoolFactory.Namespace> validation = null;
        // Cleanup may close a failed candidate, but must never close a published pool
        // that is now owned by the manager and possibly in use by another operation.
        boolean published = false;
        try {
            // Step 1: allow only one switch at a time. Waiting consumes the same budget.
            locked = switchLock.tryLock(remaining(deadline), TimeUnit.NANOSECONDS);
            if (!locked) { throw new DatabaseSwitchException(VALIDATION_TIMEOUT); }
            synchronized (stateLock) { requireOpen(); }
            // Step 2: build a separate pool and matching dialect/schema. Do not mutate
            // the active target: it must remain usable throughout candidate validation.
            candidate = factory.create(settings);
            DatabaseTarget validating = candidate;
            synchronized (stateLock) {
                requireOpen();
                // Registration and submission share the shutdown monitor, so close()
                // either observes this work or prevents it from being submitted.
                pending = candidate;
                validation = validator.submit(() -> factory.validate(validating));
                pendingValidation = validation;
            }
            // Step 3: wait outside stateLock. The worker only validates and returns
            // metadata; it cannot publish a late result after this caller times out.
            var namespace = validation.get(remaining(deadline), TimeUnit.NANOSECONDS);
            // Enable the configured idle pool size only after successful validation.
            factory.activate(candidate);
            PoolState retired;
            Map<String, Object> result = new LinkedHashMap<>(candidate.describe(namespace));
            // Step 4: atomically publish the whole target relative to acquire().
            synchronized (stateLock) {
                requireOpen();
                remaining(deadline);
                retired = active;
                if (retired != null) { retired.retired = true; }
                // New leases now use this target. Existing leases still refer to the
                // retired PoolState and therefore keep its pool and execution policy.
                active = new PoolState(candidate);
                pools.add(active);
                // Transfer ownership from the pending candidate to the managed pools.
                pending = null;
                pendingValidation = null;
                published = true;
                result.put("success", true);
                result.put("previousDatasourceId", retired == null ? null : retired.target.id);
            }
            // Step 5: reclaim the predecessor now if idle; otherwise its final lease
            // will reclaim it. Pool shutdown must not run while holding stateLock.
            closeIfUnused(retired);
            LOG.info("Activated JDBC data source {} ({})", candidate.id, candidate.dialect.name());
            return result;
        }
        catch (TimeoutException error) { throw new DatabaseSwitchException(VALIDATION_TIMEOUT); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new DatabaseSwitchException(SWITCH_INTERRUPTED);
        }
        catch (CancellationException | RejectedExecutionException error) { throw new DatabaseSwitchException(SERVICE_CLOSED); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof DatabaseSwitchException safe) { throw safe; }
            throw new DatabaseSwitchException(VALIDATION_FAILED);
        }
        catch (DatabaseSwitchException error) { throw error; }
        catch (RuntimeException error) { throw new DatabaseSwitchException(VALIDATION_FAILED); }
        finally {
            // Cancellation is best effort: a JDBC driver may ignore interruption.
            // Publication belongs only to this caller, so late validation stays inert.
            if (validation != null && !validation.isDone()) { validation.cancel(true); }
            synchronized (stateLock) {
                // Do not clear a candidate belonging to another switch when lock
                // acquisition failed or this call never registered its own candidate.
                if (pending == candidate) { pending = null; pendingValidation = null; }
            }
            if (!published && candidate != null) { candidate.close(); }
            if (locked) { switchLock.unlock(); }
        }
    }

    /** Returns the shared switch budget left, rejecting an already expired attempt. */
    private long remaining(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) { throw new DatabaseSwitchException(VALIDATION_TIMEOUT); }
        return remaining;
    }

    /**
     * Pins the target selected at acquisition time, even if a switch happens before
     * the caller obtains its JDBC connection. Each caller must close the lease after
     * closing all connections borrowed through it.
     */
    public Lease acquire() {
        synchronized (stateLock) {
            requireOpen();
            if (active == null) { throw new DatabaseSwitchException(CONNECTION_FAILED); }
            // Increment under the same monitor as publication and retirement checks.
            active.users++;
            return new Lease(active);
        }
    }

    private void requireOpen() { if (closed) { throw new DatabaseSwitchException(SERVICE_CLOSED); } }

    /** Removes an unused retired pool exactly once before closing it outside the lock. */
    private void closeIfUnused(PoolState state) {
        boolean close;
        synchronized (stateLock) {
            // Set removal elects one cleanup caller if shutdown and lease release race.
            close = state != null && state.retired && state.users == 0 && pools.remove(state);
        }
        if (close) { state.target.close(); }
    }

    /** Rejects new work, cancels pending validation, and lets existing leases drain. */
    @Override
    public void close() {
        ArrayList<PoolState> retiring;
        DatabaseTarget candidate;
        Future<?> validation;
        synchronized (stateLock) {
            if (closed) { return; }
            // Changing these flags under stateLock prevents subsequent acquisitions
            // and prevents a validating switch from publishing during shutdown.
            closed = true;
            active = null;
            retiring = new ArrayList<>(pools);
            retiring.forEach(state -> state.retired = true);
            candidate = pending;
            validation = pendingValidation;
        }
        // Perform cancellation and potentially slow resource cleanup outside stateLock.
        if (validation != null) { validation.cancel(true); }
        validator.shutdownNow();
        if (candidate != null) { candidate.close(); }
        retiring.forEach(this::closeIfUnused);
    }

    /** Mutable lifetime bookkeeping; users counts leases, not physical connections. */
    private static final class PoolState {
        final DatabaseTarget target;
        int users;
        int transactions;
        boolean retired;
        PoolState(DatabaseTarget target) { this.target = target; }
    }

    /** A stable view of one target; switching never retargets an existing lease. */
    public final class Lease implements AutoCloseable {
        private final PoolState state;
        // Makes repeated close() calls harmless and prevents double decrements.
        private final AtomicBoolean released = new AtomicBoolean();
        private boolean transactionReserved;
        private Lease(PoolState state) { this.state = state; }
        public String datasourceId() { return state.target.id; }
        public DatabaseDialect dialect() { return state.target.dialect; }
        public String schema() { return state.target.configuredSchema; }

        /** Borrows from the pinned pool; the caller closes the connection separately. */
        public Connection connection() throws SQLException {
            if (released.get()) { throw new IllegalStateException("The data source lease has been released."); }
            return state.target.pool.getConnection();
        }

        /** Discards a connection with an uncertain transaction outcome instead of reusing it. */
        void evict(Connection connection) {
            state.target.pool.evictConnection(connection);
        }

        /** Retained transactions leave one pool slot for independent tools when possible. */
        boolean reserveTransaction() {
            synchronized (stateLock) {
                if (released.get()) { return false; }
                if (transactionReserved) { return true; }
                int limit = Math.max(1, state.target.pool.getMaximumPoolSize() - 1);
                if (state.transactions >= limit) { return false; }
                state.transactions++;
                transactionReserved = true;
                return true;
            }
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                synchronized (stateLock) {
                    state.users--;
                    if (transactionReserved) { state.transactions--; }
                }
                // The final operation on a retired target performs deferred cleanup.
                closeIfUnused(state);
            }
        }
    }
}
