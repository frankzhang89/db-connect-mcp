package com.example.dbconnectmcp.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Executes estimated plans on the retained transaction connection; never commits. */
final class TransactionExplainExecutor {
    /** Session state could not be restored: the manager must discard this connection. */
    static final class CleanupFailure extends SQLException {
        CleanupFailure(Throwable cause) { super("Execution plan cleanup failed.", "HY000", cause); }
    }

    private final TransactionResultMapper mapper;
    private final int maxRows;
    private final int timeout;
    private final LongSupplier clock;

    TransactionExplainExecutor(TransactionResultMapper mapper, int maxRows, int timeout, LongSupplier clock) {
        this.mapper = mapper;
        this.maxRows = maxRows;
        this.timeout = timeout;
        this.clock = clock;
    }

    Map<String, Object> execute(Connection connection, DatabaseDialect dialect, ExplainSql explain, long deadline)
            throws SQLException {
        if (dialect instanceof OracleDialect) {
            // Oracle STATEMENT_ID is bounded to 30 characters. Never delete another plan.
            String id = "mcp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            try {
                command(connection, "EXPLAIN PLAN SET STATEMENT_ID = '" + id + "' FOR " + explain.query(), deadline);
                return query(connection, "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE', '"
                        + id + "', 'TYPICAL'))", deadline, true);
            }
            finally {
                cleanup(connection, "DELETE FROM PLAN_TABLE WHERE STATEMENT_ID = '" + id + "'");
            }
        }
        if (dialect instanceof SqlServerDialect) {
            try {
                // Separate JDBC calls are separate batches. GO is never sent to the server.
                command(connection, "SET SHOWPLAN_XML ON", deadline);
                // JDBC maxRows can issue SET ROWCOUNT. Avoid extra session commands under
                // SHOWPLAN; the result mapper still bounds the returned plan rows/text.
                return query(connection, explain.query(), deadline, false);
            }
            finally {
                // Even an unsuccessful ON call can have changed server state.
                cleanup(connection, "SET SHOWPLAN_XML OFF");
            }
        }
        return query(connection, "EXPLAIN " + explain.query(), deadline, true);
    }

    private int remaining(long deadline) throws SQLException {
        long left = deadline - clock.getAsLong();
        if (left <= 0) { throw new SQLException("Script deadline exceeded.", "HYT00"); }
        return (int) Math.min(timeout, Math.max(1, TimeUnit.NANOSECONDS.toSeconds(left)));
    }

    private void command(Connection connection, String sql, long deadline) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(remaining(deadline));
            statement.execute(sql);
        }
    }

    private Map<String, Object> query(Connection connection, String sql, long deadline, boolean jdbcRowLimit) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(remaining(deadline));
            if (jdbcRowLimit) { statement.setMaxRows(maxRows + 1); }
            try (var result = statement.executeQuery(sql)) { return mapper.read(result); }
        }
    }

    private void cleanup(Connection connection, String sql) throws CleanupFailure {
        // Restoration gets a bounded attempt even after the script deadline has expired.
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.execute(sql);
        }
        catch (SQLException | RuntimeException error) { throw new CleanupFailure(error); }
    }
}
