package com.example.dbconnectmcp.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.dbconnectmcp.database.DatabaseSwitchException.Code.*;

import com.zaxxer.hikari.HikariDataSource;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatabaseRuntimeManagerTest {
    private DatabasePoolFactory factory;
    private DatabaseTarget first;
    private DatabaseTarget second;
    private final DatabaseConnectionSettings initial = new DatabaseConnectionSettings("jdbc:mysql://one/demo", "user", "secret-one", "old_schema");
    private final DatabaseConnectionSettings next = new DatabaseConnectionSettings("jdbc:postgresql://two/demo", "user", "secret-two", null);

    @BeforeEach
    void setUp() {
        factory = mock(DatabasePoolFactory.class);
        first = new DatabaseTarget(mock(HikariDataSource.class), new MySqlDialect(), "old_schema");
        second = new DatabaseTarget(mock(HikariDataSource.class), new PostgreSqlDialect(), null);
        when(factory.create(initial)).thenReturn(first);
        when(factory.create(next)).thenReturn(second);
        when(factory.validate(any())).thenReturn(new DatabasePoolFactory.Namespace("demo", "public"));
    }

    private DatabaseRuntimeManager started(int timeout) {
        var manager = new DatabaseRuntimeManager(factory, new DatabaseSwitchProperties(timeout, 1));
        manager.switchTo(initial);
        return manager;
    }

    @Test
    void switchesPoolDialectAndSchemaTogetherAndDrainsExistingOperations() throws Exception {
        try (var manager = started(5)) {
            var old = manager.acquire();
            var result = manager.switchTo(next);
            assertEquals(first.id, result.get("previousDatasourceId"));
            assertEquals(second.id, result.get("datasourceId"));
            assertEquals("PostgreSQL", result.get("databaseType"));
            assertFalse(result.toString().contains("secret"));
            try (var current = manager.acquire()) {
                assertEquals(second.id, current.datasourceId());
                assertInstanceOf(PostgreSqlDialect.class, current.dialect());
                assertNull(current.schema());
                assertEquals(first.id, old.datasourceId());
                assertInstanceOf(MySqlDialect.class, old.dialect());
                assertEquals("old_schema", old.schema());
                verify(first.pool, never()).close();
                old.connection();
                verify(first.pool).getConnection();
            }
            old.close();
            old.close();
            verify(first.pool, times(1)).close();
            assertThrows(IllegalStateException.class, old::connection);
        }
        verify(second.pool, times(1)).close();
    }

    @Test
    void failedValidationClosesCandidateAndLeavesCurrentPoolAvailable() {
        when(factory.validate(second)).thenThrow(new DatabaseSwitchException(AUTHENTICATION_FAILED));
        try (var manager = started(5)) {
            assertEquals(AUTHENTICATION_FAILED, assertThrows(DatabaseSwitchException.class, () -> manager.switchTo(next)).code());
            verify(second.pool).close();
            verify(first.pool, never()).close();
            try (var lease = manager.acquire()) { assertEquals(first.id, lease.datasourceId()); }
        }
    }

    @Test
    void invalidSettingsDoNotDisturbTheCurrentPoolAndUnexpectedDriverErrorsAreSanitized() {
        when(factory.create(next)).thenThrow(new IllegalArgumentException("password=secret-two private-host"));
        try (var manager = started(5)) {
            var error = assertThrows(DatabaseSwitchException.class, () -> manager.switchTo(next));
            assertFalse(error.toString().contains("secret"));
            assertNull(error.getCause());
            try (var lease = manager.acquire()) { assertEquals(first.id, lease.datasourceId()); }
        }
    }

    @Test
    void slowValidationDoesNotBlockQueriesAndLateCompletionCannotPublish() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        when(factory.validate(second)).thenAnswer(call -> {
            entered.countDown();
            // Model a driver that ignores interruption until its network operation finishes.
            while (true) {
                try { release.await(5, TimeUnit.SECONDS); break; }
                catch (InterruptedException ignored) { }
            }
            completed.countDown();
            return new DatabasePoolFactory.Namespace("demo", "public");
        });
        try (var manager = started(1); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> switching = callers.submit(() -> manager.switchTo(next));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            try (var lease = manager.acquire()) { assertEquals(first.id, lease.datasourceId()); }
            var error = assertThrows(ExecutionException.class, () -> switching.get(3, TimeUnit.SECONDS));
            assertEquals(VALIDATION_TIMEOUT, ((DatabaseSwitchException) error.getCause()).code());
            verify(second.pool).close();
            release.countDown();
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            try (var lease = manager.acquire()) { assertEquals(first.id, lease.datasourceId()); }
        }
        finally { release.countDown(); }
    }

    @Test
    void concurrentSwitchesAreSerializedAndEachReportsItsActualPredecessor() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var thirdSettings = new DatabaseConnectionSettings("jdbc:oracle:thin:@//three/demo", "user", "secret-three", null);
        var third = new DatabaseTarget(mock(HikariDataSource.class), new OracleDialect(), null);
        when(factory.create(thirdSettings)).thenReturn(third);
        when(factory.validate(second)).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return new DatabasePoolFactory.Namespace("demo", "public");
        });
        try (var manager = started(5); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = callers.submit(() -> manager.switchTo(next));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var b = callers.submit(() -> manager.switchTo(thirdSettings));
            verify(factory, never()).create(thirdSettings);
            release.countDown();
            assertEquals(first.id, a.get(3, TimeUnit.SECONDS).get("previousDatasourceId"));
            assertEquals(second.id, b.get(3, TimeUnit.SECONDS).get("previousDatasourceId"));
            verify(first.pool).close();
            verify(second.pool).close();
        }
        finally { release.countDown(); }
        verify(third.pool).close();
    }

    @Test
    void shutdownRejectsNewWorkButLetsExistingLeasesFinish() {
        var manager = started(5);
        var lease = manager.acquire();
        manager.close();
        manager.close();
        verify(first.pool, never()).close();
        assertEquals(SERVICE_CLOSED, assertThrows(DatabaseSwitchException.class, manager::acquire).code());
        assertEquals(SERVICE_CLOSED, assertThrows(DatabaseSwitchException.class, () -> manager.switchTo(next)).code());
        lease.close();
        verify(first.pool, times(1)).close();
    }

    @Test
    void shutdownAfterPublicationDoesNotTreatTheActivePoolAsAnUnvalidatedCandidate() throws Exception {
        CountDownLatch closingOld = new CountDownLatch(1);
        CountDownLatch releaseOldClose = new CountDownLatch(1);
        doAnswer(call -> {
            closingOld.countDown();
            assertTrue(releaseOldClose.await(3, TimeUnit.SECONDS));
            return null;
        }).when(first.pool).close();
        var manager = started(5);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var switching = callers.submit(() -> manager.switchTo(next));
            assertTrue(closingOld.await(2, TimeUnit.SECONDS));
            var lease = manager.acquire();
            assertEquals(second.id, lease.datasourceId());
            manager.close();
            verify(second.pool, never()).close();
            lease.close();
            verify(second.pool, times(1)).close();
            releaseOldClose.countDown();
            assertEquals(second.id, switching.get(3, TimeUnit.SECONDS).get("datasourceId"));
        }
        finally { releaseOldClose.countDown(); manager.close(); }
    }

    @Test
    void shutdownDuringValidationCancelsTheCandidateAndNeverPublishesIt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        when(factory.validate(second)).thenAnswer(call -> { entered.countDown(); new CountDownLatch(1).await(); return null; });
        var manager = started(5);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var switching = callers.submit(() -> manager.switchTo(next));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            manager.close();
            var error = assertThrows(ExecutionException.class, () -> switching.get(3, TimeUnit.SECONDS));
            assertEquals(SERVICE_CLOSED, ((DatabaseSwitchException) error.getCause()).code());
        }
        finally { manager.close(); }
        verify(first.pool).close();
        verify(second.pool, times(1)).close();
    }
}
