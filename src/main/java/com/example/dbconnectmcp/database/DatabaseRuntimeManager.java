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

/** Serializes switches while normal operations independently lease a stable target. */
public class DatabaseRuntimeManager implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseRuntimeManager.class);
    private final Object stateLock = new Object();
    private final ReentrantLock switchLock = new ReentrantLock(true);
    private final DatabasePoolFactory factory;
    private final DatabaseSwitchProperties timeouts;
    private final ExecutorService validator = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("db-validation-", 0).factory());
    private final Set<PoolState> pools = new HashSet<>();
    private PoolState active;
    private boolean closed;
    private DatabaseTarget pending;
    private Future<DatabasePoolFactory.Namespace> pendingValidation;

    public DatabaseRuntimeManager(DatabasePoolFactory factory, DatabaseSwitchProperties timeouts) {
        this.factory = factory;
        this.timeouts = timeouts;
    }

    public Map<String, Object> switchTo(DatabaseConnectionSettings settings) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeouts.timeoutSeconds());
        boolean locked = false;
        DatabaseTarget candidate = null;
        Future<DatabasePoolFactory.Namespace> validation = null;
        boolean published = false;
        try {
            locked = switchLock.tryLock(remaining(deadline), TimeUnit.NANOSECONDS);
            if (!locked) { throw new DatabaseSwitchException(VALIDATION_TIMEOUT); }
            synchronized (stateLock) { requireOpen(); }
            candidate = factory.create(settings);
            DatabaseTarget validating = candidate;
            synchronized (stateLock) {
                requireOpen();
                pending = candidate;
                validation = validator.submit(() -> factory.validate(validating));
                pendingValidation = validation;
            }
            var namespace = validation.get(remaining(deadline), TimeUnit.NANOSECONDS);
            factory.activate(candidate);
            PoolState retired;
            Map<String, Object> result = new LinkedHashMap<>(candidate.describe(namespace));
            synchronized (stateLock) {
                requireOpen();
                remaining(deadline);
                retired = active;
                if (retired != null) { retired.retired = true; }
                active = new PoolState(candidate);
                pools.add(active);
                pending = null;
                pendingValidation = null;
                published = true;
                result.put("success", true);
                result.put("previousDatasourceId", retired == null ? null : retired.target.id);
            }
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
            if (validation != null && !validation.isDone()) { validation.cancel(true); }
            synchronized (stateLock) {
                if (pending == candidate) { pending = null; pendingValidation = null; }
            }
            if (!published && candidate != null) { candidate.close(); }
            if (locked) { switchLock.unlock(); }
        }
    }

    private long remaining(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) { throw new DatabaseSwitchException(VALIDATION_TIMEOUT); }
        return remaining;
    }

    public Lease acquire() {
        synchronized (stateLock) {
            requireOpen();
            if (active == null) { throw new DatabaseSwitchException(CONNECTION_FAILED); }
            active.users++;
            return new Lease(active);
        }
    }

    private void requireOpen() { if (closed) { throw new DatabaseSwitchException(SERVICE_CLOSED); } }

    private void closeIfUnused(PoolState state) {
        boolean close;
        synchronized (stateLock) {
            close = state != null && state.retired && state.users == 0 && pools.remove(state);
        }
        if (close) { state.target.close(); }
    }

    @Override
    public void close() {
        ArrayList<PoolState> retiring;
        DatabaseTarget candidate;
        Future<?> validation;
        synchronized (stateLock) {
            if (closed) { return; }
            closed = true;
            active = null;
            retiring = new ArrayList<>(pools);
            retiring.forEach(state -> state.retired = true);
            candidate = pending;
            validation = pendingValidation;
        }
        if (validation != null) { validation.cancel(true); }
        validator.shutdownNow();
        if (candidate != null) { candidate.close(); }
        retiring.forEach(this::closeIfUnused);
    }

    private static final class PoolState {
        final DatabaseTarget target;
        int users;
        boolean retired;
        PoolState(DatabaseTarget target) { this.target = target; }
    }

    public final class Lease implements AutoCloseable {
        private final PoolState state;
        private final AtomicBoolean released = new AtomicBoolean();
        private Lease(PoolState state) { this.state = state; }
        public String datasourceId() { return state.target.id; }
        public DatabaseDialect dialect() { return state.target.dialect; }
        public String schema() { return state.target.configuredSchema; }

        public Connection connection() throws SQLException {
            if (released.get()) { throw new IllegalStateException("The data source lease has been released."); }
            return state.target.pool.getConnection();
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                synchronized (stateLock) { state.users--; }
                closeIfUnused(state);
            }
        }
    }
}
