package com.example.dbconnectmcp.database;

import com.zaxxer.hikari.HikariDataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One pool together with the dialect and metadata schema that must always accompany it.
 * The manager publishes this unit as a whole so a switch cannot mix old connections
 * with a new database's policy. Credentials stay inside the pool's data source.
 */
final class DatabaseTarget implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseTarget.class);
    // Identifies this pool generation, even when switching to the same JDBC address.
    final String id = "ds-" + UUID.randomUUID();
    final HikariDataSource pool;
    final DatabaseDialect dialect;
    final String configuredSchema;
    private final AtomicBoolean closed = new AtomicBoolean();

    DatabaseTarget(HikariDataSource pool, DatabaseDialect dialect, String schema) {
        this.pool = pool;
        this.dialect = dialect;
        this.configuredSchema = schema;
    }

    /** Describes the validated target without exposing its connection settings. */
    Map<String, Object> describe(DatabasePoolFactory.Namespace namespace) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasourceId", id);
        result.put("databaseType", dialect.name());
        if (namespace.catalog() != null) { result.put("catalog", namespace.catalog()); }
        if (namespace.schema() != null) { result.put("schema", namespace.schema()); }
        return result;
    }

    @Override
    public void close() {
        // Candidate cleanup and shutdown may race; the underlying pool closes once.
        if (closed.compareAndSet(false, true)) {
            try { pool.close(); }
            catch (RuntimeException error) {
                // Cleanup must not turn an already committed write into a reported failure.
                LOG.warn("Could not close database pool {}", id);
            }
        }
    }

    @Override
    public String toString() { return "DatabaseTarget[id=" + id + ", type=" + dialect.name() + "]"; }
}
