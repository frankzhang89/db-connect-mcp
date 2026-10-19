package com.example.dbconnectmcp.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real JDBC tests establish commit, rollback, and cross-connection visibility using H2. */
class DatabaseTransactionManagerTest {
    private final List<DatabaseTarget> targets = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong();
    private DatabaseRuntimeManager runtime;
    private DatabaseTransactionManager manager;
    private DatabasePoolFactory factory;
    private String url;
    private final DatabaseConnectionSettings settings = new DatabaseConnectionSettings("jdbc:test:first", "sa", "", null);

    @BeforeEach
    void start() throws Exception {
        factory = mock(DatabasePoolFactory.class);
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000";
        var first = target(url, new PostgreSqlDialect());
        when(factory.create(settings)).thenReturn(first);
        when(factory.validate(any())).thenReturn(new DatabasePoolFactory.Namespace("test", "PUBLIC"));
        runtime = new DatabaseRuntimeManager(factory, new DatabaseSwitchProperties(5, 1));
        runtime.switchTo(settings);
        manager = new DatabaseTransactionManager(runtime, new DatabaseTransactionProperties(120, 600, 5, 20, 20000, 30), 5, 2, 8, clock::get);
        try (Connection connection = observer(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE account(id INT PRIMARY KEY, balance INT, note VARCHAR(100))");
            statement.execute("INSERT INTO account VALUES(1,1000,'original'),(2,500,'second')");
        }
    }

    @AfterEach
    void stop() throws Exception {
        manager.close();
        runtime.close();
        for (var target : targets) { target.close(); }
        try (Connection connection = observer(); var statement = connection.createStatement()) { statement.execute("SHUTDOWN"); }
    }

    private DatabaseTarget target(String address, DatabaseDialect dialect) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(address);
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(6);
        config.setMinimumIdle(0);
        var target = new DatabaseTarget(new HikariDataSource(config), dialect, null);
        targets.add(target);
        return target;
    }

    private Connection observer() throws SQLException { return DriverManager.getConnection(url, "sa", ""); }
    private String begin(String owner) {
        var result = manager.execute(owner, "BEGIN", null);
        assertEquals(true, result.get("success"), result.toString());
        return (String) result.get("transactionId");
    }
    private Map<String, Object> execute(String id, String sql) {
        var result = manager.execute("session-a", sql, id);
        assertEquals(true, result.get("success"), result.toString());
        return result;
    }
    private int balance() throws SQLException {
        try (Connection connection = observer(); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT balance FROM account WHERE id=1")) {
            result.next(); return result.getInt(1);
        }
    }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> results(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("results");
    }

    @Test
    void readsOwnWritesWithoutExposingThemThenRollsBackOrCommits() throws Exception {
        String id = begin("session-a");
        var result = execute(id, "UPDATE account SET balance=900 WHERE id=1; SELECT balance FROM account WHERE id=1");
        assertEquals(1L, results(result).get(0).get("affectedRows"));
        assertEquals(List.of(Map.of("BALANCE", 900)), results(result).get(1).get("rows"));
        assertEquals(1000, balance());
        assertEquals("ROLLED_BACK", execute(id, "ROLLBACK").get("state"));
        assertEquals(1000, balance());
        id = begin("session-a");
        execute(id, "UPDATE account SET balance=900 WHERE id=1");
        assertEquals("COMMITTED", execute(id, "COMMIT").get("state"));
        assertEquals(900, balance());
        assertEquals("TRANSACTION_NOT_FOUND", manager.execute("session-a", "COMMIT", id).get("code"));
    }

    @Test
    void separatesIdsWithinOneSessionAndRejectsCrossSessionAccess() {
        String a = begin("session-a");
        String b = begin("session-a");
        execute(a, "UPDATE account SET balance=900 WHERE id=1");
        assertEquals(List.of(Map.of("BALANCE", 1000)), results(execute(b, "SELECT balance FROM account WHERE id=1")).getFirst().get("rows"));
        assertEquals("TRANSACTION_NOT_FOUND", manager.execute("session-b", "ROLLBACK", a).get("code"));
        assertEquals("NESTED_TRANSACTION", manager.execute("session-a", "BEGIN", a).get("code"));
        assertEquals("TRANSACTION_REQUIRED", manager.execute("session-a", "UPDATE account SET balance=1", null).get("code"));
        execute(a, "ROLLBACK");
        execute(b, "ROLLBACK");
    }

    @Test
    void prevalidatesWholeScriptAndRequiresRollbackAfterAnExecutionFailure() throws Exception {
        String id = begin("session-a");
        assertEquals("SQL_REJECTED", manager.execute("session-a", "UPDATE account SET balance=1; DROP TABLE account", id).get("code"));
        assertEquals(List.of(Map.of("BALANCE", 1000)), results(execute(id, "SELECT balance FROM account WHERE id=1")).getFirst().get("rows"));
        var failed = manager.execute("session-a", "UPDATE account SET balance=900 WHERE id=1; INSERT INTO missing_table VALUES(1); UPDATE account SET balance=0", id);
        assertEquals("EXECUTION_FAILED", failed.get("code"));
        assertEquals(1, failed.get("statementIndex"));
        assertEquals("ROLLBACK_ONLY", failed.get("state"));
        assertEquals("ROLLBACK_REQUIRED", manager.execute("session-a", "COMMIT", id).get("code"));
        assertEquals("ROLLBACK_REQUIRED", manager.execute("session-a", "SELECT 1", id).get("code"));
        assertFalse(failed.toString().contains("missing_table"));
        execute(id, "ROLLBACK");
        assertEquals(1000, balance());
    }

    @Test
    void pinsOriginalPoolAcrossSwitchAndClosesItAfterTransactionEnds() {
        String id = begin("session-a");
        String oldId = (String) execute(id, "SELECT 1").get("datasourceId");
        var other = new DatabaseConnectionSettings("jdbc:test:second", "sa", "", null);
        var next = target("jdbc:h2:mem:" + UUID.randomUUID(), new PostgreSqlDialect());
        when(factory.create(other)).thenReturn(next);
        runtime.switchTo(other);
        assertFalse(targets.getFirst().pool.isClosed());
        assertEquals(oldId, execute(id, "SELECT balance FROM account WHERE id=1").get("datasourceId"));
        String newId = begin("session-a");
        assertEquals(next.id, execute(newId, "SELECT 1").get("datasourceId"));
        execute(id, "ROLLBACK");
        assertTrue(targets.getFirst().pool.isClosed());
        execute(newId, "ROLLBACK");
    }

    @Test
    void idleExpiryLifetimeSessionCloseAndShutdownRollbackRetainedConnections() throws Exception {
        String id = begin("session-a");
        execute(id, "UPDATE account SET balance=900 WHERE id=1");
        clock.addAndGet(TimeUnit.SECONDS.toNanos(121));
        manager.expireIdle();
        assertEquals("TRANSACTION_NOT_FOUND", manager.execute("session-a", "SELECT 1", id).get("code"));
        assertEquals(1000, balance());
        id = begin("session-a");
        for (int i = 0; i < 5; i++) { clock.addAndGet(TimeUnit.SECONDS.toNanos(100)); execute(id, "SELECT 1"); }
        clock.addAndGet(TimeUnit.SECONDS.toNanos(101));
        assertEquals("TRANSACTION_EXPIRED", manager.execute("session-a", "SELECT 1", id).get("code"));
        id = begin("session-a");
        execute(id, "UPDATE account SET balance=900 WHERE id=1");
        manager.closeSession("session-a");
        assertEquals(1000, balance());
        id = begin("session-b");
        assertEquals(true, manager.execute("session-b", "UPDATE account SET balance=900 WHERE id=1", id).get("success"));
        manager.close();
        assertEquals(1000, balance());
        assertEquals("SERVICE_CLOSED", manager.execute("session-a", "BEGIN", null).get("code"));
    }

    @Test
    void boundsReturnedRowsAndCellsWithoutLimitingDmlAffectedRows() {
        String id = begin("session-a");
        execute(id, "INSERT INTO account VALUES(3,100,'third')");
        var query = results(execute(id, "SELECT * FROM account ORDER BY id")).getFirst();
        assertEquals(true, query.get("truncated"));
        assertEquals(2, query.get("rowCount"));
        var update = results(execute(id, "UPDATE account SET note='longer than eight'")).getFirst();
        assertEquals(3L, update.get("affectedRows"));
        var rows = results(execute(id, "SELECT note FROM account WHERE id=1")).getFirst().get("rows");
        assertEquals(List.of(Map.of("NOTE", Map.of("text", "longer t", "truncated", true))), rows);
        execute(id, "ROLLBACK");
    }

    @Test
    void reportsImplicitCommitScopeForTruncateAndAllowsItDespiteDdlRestrictions() throws Exception {
        var other = new DatabaseConnectionSettings("jdbc:test:mysql-policy", "sa", "", null);
        var mysqlPolicy = target(url, new MySqlDialect());
        when(factory.create(other)).thenReturn(mysqlPolicy);
        runtime.switchTo(other);
        String id = begin("session-a");
        execute(id, "UPDATE account SET balance=900 WHERE id=1");
        var truncated = execute(id, "TRUNCATE TABLE account");
        assertEquals(true, truncated.get("implicitCommitPossible"));
        assertEquals("SINCE_LAST_IMPLICIT_COMMIT", truncated.get("rollbackScope"));
        assertTrue(truncated.containsKey("warnings"));
        assertEquals(true, execute(id, "ROLLBACK").get("implicitCommitPossible"));
        try (Connection connection = observer(); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM account")) {
            result.next(); assertEquals(0, result.getInt(1));
        }
    }

    @Test
    void capacityIsReclaimedAndUnsupportedDatabasesDoNotOpenConnections() {
        manager.close();
        manager = new DatabaseTransactionManager(runtime, new DatabaseTransactionProperties(120, 600, 1, 20, 20000, 30), 5, 100, 4000);
        String id = begin("session-a");
        assertEquals("TRANSACTION_LIMIT", manager.execute("session-a", "BEGIN", null).get("code"));
        execute(id, "ROLLBACK");
        execute(begin("session-a"), "ROLLBACK");
        var other = new DatabaseConnectionSettings("jdbc:test:starrocks-policy", "sa", "", null);
        var unsupported = target(url, new StarRocksDialect());
        when(factory.create(other)).thenReturn(unsupported);
        runtime.switchTo(other);
        assertEquals("TRANSACTIONS_UNSUPPORTED", manager.execute("session-a", "BEGIN", null).get("code"));
        assertEquals(0, unsupported.pool.getHikariPoolMXBean().getActiveConnections());
    }

    @Test
    void serializesCallsOnOneIdWhileOtherIdsRemainUsable() throws Exception {
        // Hold a database row lock externally so the first tool call stays in flight.
        String a = begin("session-a");
        String b = begin("session-a");
        try (Connection blocker = observer(); var statement = blocker.createStatement();
                var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            statement.executeUpdate("UPDATE account SET balance=800 WHERE id=1");
            CountDownLatch started = new CountDownLatch(1);
            var update = callers.submit(() -> { started.countDown(); return manager.execute("session-a", "UPDATE account SET balance=900 WHERE id=1", a); });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> {
                try (Connection inspection = observer(); var query = inspection.createStatement();
                        var result = query.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_STATE='BLOCKED'")) {
                    result.next(); return result.getInt(1) > 0;
                }
            });
            // A second ID does not wait on the first ID's execution lock.
            assertEquals(true, manager.execute("session-a", "SELECT 1", b).get("success"));
            var rollback = callers.submit(() -> manager.execute("session-a", "ROLLBACK", a));
            blocker.rollback();
            assertEquals(true, update.get(3, TimeUnit.SECONDS).get("success"));
            assertEquals("ROLLED_BACK", rollback.get(3, TimeUnit.SECONDS).get("state"));
            assertEquals(1000, balance());
        }
        execute(b, "ROLLBACK");
    }

    @Test
    void neverRetriesACommitWhoseAcknowledgementWasLostAndDiscardsTheConnection() throws Exception {
        manager.close();
        var retained = spy(observer());
        var runtimeMock = mock(DatabaseRuntimeManager.class);
        var lease = mock(DatabaseRuntimeManager.Lease.class);
        when(runtimeMock.acquire()).thenReturn(lease);
        when(lease.dialect()).thenReturn(new PostgreSqlDialect());
        when(lease.datasourceId()).thenReturn("test-pinned-target");
        when(lease.reserveTransaction()).thenReturn(true);
        when(lease.connection()).thenReturn(retained);
        doAnswer(call -> {
            call.callRealMethod();
            throw new SQLException("secret-driver-address-password", "08006");
        }).when(retained).commit();
        manager = new DatabaseTransactionManager(runtimeMock, new DatabaseTransactionProperties(120, 600, 5, 20, 20000, 30), 5, 100, 4000);
        String id = begin("session-a");
        execute(id, "UPDATE account SET balance=900 WHERE id=1");
        var unknown = manager.execute("session-a", "COMMIT", id);
        assertEquals("COMMIT_OUTCOME_UNKNOWN", unknown.get("code"));
        assertEquals("UNKNOWN", unknown.get("state"));
        assertFalse(unknown.toString().contains("secret-driver"));
        assertEquals(900, balance());
        assertEquals("TRANSACTION_NOT_FOUND", manager.execute("session-a", "COMMIT", id).get("code"));
        verify(retained, times(1)).commit();
        verify(lease).evict(retained);
        verify(lease).close();
        assertTrue(retained.isClosed());
    }

    @Test
    void stopsAtTheScriptDeadlineWithoutExecutingLaterStatements() throws Exception {
        manager.close();
        var retained = spy(observer());
        var runtimeMock = mock(DatabaseRuntimeManager.class);
        var lease = mock(DatabaseRuntimeManager.Lease.class);
        when(runtimeMock.acquire()).thenReturn(lease);
        when(lease.dialect()).thenReturn(new PostgreSqlDialect());
        when(lease.datasourceId()).thenReturn("test-deadline-target");
        when(lease.reserveTransaction()).thenReturn(true);
        when(lease.connection()).thenReturn(retained);
        doAnswer(call -> {
            var statement = spy((java.sql.Statement) call.callRealMethod());
            doAnswer(execution -> {
                Object result = execution.callRealMethod();
                clock.addAndGet(TimeUnit.SECONDS.toNanos(31));
                return result;
            }).when(statement).execute(anyString());
            return statement;
        }).when(retained).createStatement();
        manager = new DatabaseTransactionManager(runtimeMock, new DatabaseTransactionProperties(120, 600, 5, 20, 20000, 30),
                5, 100, 4000, clock::get);
        String id = begin("session-a");
        var failed = manager.execute("session-a", "UPDATE account SET balance=900 WHERE id=1; DELETE FROM account", id);
        assertEquals("EXECUTION_FAILED", failed.get("code"));
        assertEquals("ROLLBACK_ONLY", failed.get("state"));
        assertEquals(0, failed.get("statementIndex"));
        try (var statement = retained.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM account")) {
            result.next(); assertEquals(2, result.getInt(1));
        }
        execute(id, "ROLLBACK");
        assertEquals(1000, balance());
    }
}
