package com.example.dbconnectmcp.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** JDBC doubles verify vendor command sequencing; these are not live engine tests. */
class DatabaseExplainTest {
    private static final class Fixture implements AutoCloseable {
        final DatabaseRuntimeManager runtime = mock(DatabaseRuntimeManager.class);
        final DatabaseRuntimeManager.Lease lease = mock(DatabaseRuntimeManager.Lease.class);
        final Connection connection = mock(Connection.class);
        final Statement setup = mock(Statement.class);
        final Statement query = mock(Statement.class);
        final Statement cleanup = mock(Statement.class);
        final DatabaseTransactionManager manager;
        final String id;

        Fixture(DatabaseDialect dialect) throws Exception {
            var metadata = mock(DatabaseMetaData.class);
            when(connection.getMetaData()).thenReturn(metadata);
            when(metadata.supportsTransactions()).thenReturn(true);
            when(runtime.acquire()).thenReturn(lease);
            when(lease.connection()).thenReturn(connection);
            when(lease.dialect()).thenReturn(dialect);
            when(lease.datasourceId()).thenReturn("ds-plan");
            when(lease.reserveTransaction()).thenReturn(true);
            when(connection.createStatement()).thenReturn(setup, query, cleanup);
            var rows = mock(ResultSet.class);
            var columns = mock(ResultSetMetaData.class);
            when(query.executeQuery(anyString())).thenReturn(rows);
            when(rows.getMetaData()).thenReturn(columns);
            when(columns.getColumnCount()).thenReturn(1);
            when(columns.getColumnLabel(1)).thenReturn("plan");
            when(rows.next()).thenReturn(true, false);
            when(rows.getObject(1)).thenReturn("estimated plan");
            manager = new DatabaseTransactionManager(runtime,
                    new DatabaseTransactionProperties(120, 600, 5, 20, 20000, 30), 5, 2, 4000);
            var begin = manager.execute("owner", "BEGIN", null);
            assertEquals(true, begin.get("success"), begin.toString());
            id = (String) begin.get("transactionId");
        }

        Map<String, Object> execute(String sql) { return manager.execute("owner", sql, id); }
        @Override public void close() { manager.close(); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> plan(Map<String, Object> response) {
        return ((List<Map<String, Object>>) response.get("results")).getFirst();
    }

    @Test
    void sqlServerUsesSeparateBatchesAndRestoresStateWithoutCommitting() throws Exception {
        try (var f = new Fixture(new SqlServerDialect())) {
            var response = f.execute("EXPLAIN SELECT id FROM users");
            assertEquals(true, response.get("success"));
            assertEquals("ACTIVE", response.get("state"));
            assertEquals("ds-plan", response.get("datasourceId"));
            assertEquals(List.of(Map.of("plan", "estimated plan")), plan(response).get("rows"));
            var order = inOrder(f.setup, f.query, f.cleanup);
            order.verify(f.setup).execute("SET SHOWPLAN_XML ON");
            order.verify(f.query).executeQuery("SELECT id FROM users");
            order.verify(f.query).close();
            order.verify(f.cleanup).execute("SET SHOWPLAN_XML OFF");
            verify(f.connection, never()).commit();
            assertEquals("ROLLED_BACK", f.execute("ROLLBACK").get("state"));
            verify(f.connection).rollback();
        }
    }

    @Test
    void sqlServerQueryFailureRestoresStateAndRequiresRollback() throws Exception {
        try (var f = new Fixture(new SqlServerDialect())) {
            when(f.query.executeQuery(anyString())).thenThrow(new SQLException("secret-host", "42000"));
            var response = f.execute("EXPLAIN SELECT id FROM users");
            assertEquals("EXECUTION_FAILED", response.get("code"));
            assertEquals("ROLLBACK_ONLY", response.get("state"));
            assertFalse(response.toString().contains("secret-host"));
            verify(f.cleanup).execute("SET SHOWPLAN_XML OFF");
            assertEquals("ROLLBACK_REQUIRED", f.execute("COMMIT").get("code"));
            assertEquals("ROLLED_BACK", f.execute("ROLLBACK").get("state"));
        }
    }

    @Test
    void sqlServerFailedEnableStillAttemptsDisable() throws Exception {
        try (var f = new Fixture(new SqlServerDialect())) {
            when(f.setup.execute("SET SHOWPLAN_XML ON")).thenThrow(new SQLException("enable lost", "08006"));
            // No query statement was allocated: the second statement is the cleanup call.
            var response = f.execute("EXPLAIN SELECT id FROM users");
            assertEquals("EXECUTION_FAILED", response.get("code"));
            verify(f.query).execute("SET SHOWPLAN_XML OFF");
            verify(f.query, never()).executeQuery(anyString());
        }
    }

    @Test
    void failedDisableInvalidatesHandleAndEvictsConnection() throws Exception {
        try (var f = new Fixture(new SqlServerDialect())) {
            when(f.cleanup.execute("SET SHOWPLAN_XML OFF")).thenThrow(new SQLException("secret", "08006"));
            var response = f.execute("EXPLAIN SELECT id FROM users");
            assertEquals("PLAN_CLEANUP_FAILED", response.get("code"));
            assertEquals("UNKNOWN", response.get("state"));
            assertFalse(response.toString().contains("secret"));
            verify(f.lease).evict(f.connection);
            verify(f.connection).close();
            verify(f.lease).close();
            assertEquals(0, f.manager.activeCount());
            assertEquals("TRANSACTION_NOT_FOUND", f.execute("COMMIT").get("code"));
            verify(f.connection, never()).commit();
        }
    }

    @Test
    void oracleGeneratesReadsAndDeletesOnlyItsOwnPlanOnTheRetainedConnection() throws Exception {
        for (String sql : List.of("EXPLAIN SELECT id FROM users", "EXPLAIN PLAN FOR SELECT id FROM users")) {
            try (var f = new Fixture(new OracleDialect())) {
                var response = f.execute(sql);
                assertEquals(true, response.get("success"), response.toString());
                assertEquals("QUERY", plan(response).get("type"));
                var command = ArgumentCaptor.forClass(String.class);
                verify(f.setup).execute(command.capture());
                String text = command.getValue();
                assertTrue(text.matches("EXPLAIN PLAN SET STATEMENT_ID = 'mcp_[a-f0-9]{24}' FOR SELECT id FROM users"));
                String id = text.split("'")[1];
                var order = inOrder(f.setup, f.query, f.cleanup);
                order.verify(f.setup).execute(text);
                order.verify(f.query).executeQuery("SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE', '"
                        + id + "', 'TYPICAL'))");
                order.verify(f.cleanup).execute("DELETE FROM PLAN_TABLE WHERE STATEMENT_ID = '" + id + "'");
                verify(f.query).setMaxRows(3);
                verify(f.connection, never()).commit();
                verify(f.connection, never()).rollback();
                assertEquals("ROLLED_BACK", f.execute("ROLLBACK").get("state"));
            }
        }
    }

    @Test
    void oraclePlanReadFailureStillCleansUpAndLeavesRollbackAvailable() throws Exception {
        try (var f = new Fixture(new OracleDialect())) {
            when(f.query.executeQuery(anyString())).thenThrow(new SQLException("display failed", "42000"));
            var response = f.execute("EXPLAIN SELECT id FROM users");
            assertEquals("ROLLBACK_ONLY", response.get("state"));
            verify(f.cleanup).execute(startsWith("DELETE FROM PLAN_TABLE WHERE STATEMENT_ID = 'mcp_"));
            assertEquals("ROLLED_BACK", f.execute("ROLLBACK").get("state"));
        }
    }

    @Test
    void wrongDialectAndUnsafeExplainRejectEntireScriptBeforeAnyWrite() throws Exception {
        try (var f = new Fixture(new SqlServerDialect())) {
            for (String sql : List.of("UPDATE users SET id=2; EXPLAIN PLAN FOR SELECT id FROM users",
                    "UPDATE users SET id=2; EXPLAIN ANALYZE SELECT id FROM users",
                    "UPDATE users SET id=2; EXPLAIN SELECT id FROM users WHERE id=?")) {
                assertEquals("SQL_REJECTED", f.execute(sql).get("code"), sql);
            }
            verify(f.connection, never()).createStatement();
            assertEquals("COMMITTED", f.execute("COMMIT").get("state"));
        }
    }

    @Test
    void cleanupIsAttemptedEvenWhenPlanExecutionExceedsDeadline() throws Exception {
        var connection = mock(Connection.class);
        var on = mock(Statement.class);
        var off = mock(Statement.class);
        when(connection.createStatement()).thenReturn(on, off);
        var clock = new java.util.concurrent.atomic.AtomicLong();
        when(on.execute(anyString())).thenAnswer(call -> { clock.set(10); return false; });
        var executor = new TransactionExplainExecutor(new TransactionResultMapper(2, 4000), 2, 5, clock::get);
        // Deadline expires before a query can be prepared; cleanup still receives its own timeout.
        assertThrows(SQLException.class, () -> executor.execute(connection, new SqlServerDialect(),
                new ExplainSql("SELECT 1", false), 1));
        verify(off).execute("SET SHOWPLAN_XML OFF");
        verify(off).setQueryTimeout(5);
    }
}
