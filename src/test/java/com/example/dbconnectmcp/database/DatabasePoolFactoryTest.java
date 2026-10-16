package com.example.dbconnectmcp.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.dbconnectmcp.database.DatabaseSwitchException.Code.*;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.*;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DatabasePoolFactoryTest {
    private final DatabaseProperties settings = new DatabaseProperties("jdbc:mysql://private-host/demo", "user", "private-password", 3, 0, 500L, null);
    private final DatabasePoolFactory factory = new DatabasePoolFactory(settings, new DatabaseSwitchProperties(5, 1),
            new DatabaseDialectRegistry(List.of(new MySqlDialect(), new OracleDialect(), new SqlServerDialect(), new PostgreSqlDialect(), new StarRocksDialect())));

    @Test
    void rejectsIncompleteSettingsBeforeCreatingPoolsAndRedactsDiagnosticObjects() {
        for (var invalid : List.of(
                new DatabaseConnectionSettings("jdbc:unknown://private-host/demo", "user", "private-password", null),
                new DatabaseConnectionSettings(null, "user", "private-password", null),
                new DatabaseConnectionSettings(settings.url(), "", "private-password", null),
                new DatabaseConnectionSettings(settings.url(), "user", null, null))) {
            var error = assertThrows(DatabaseSwitchException.class, () -> factory.create(invalid));
            assertEquals(INVALID_ARGUMENT, error.code());
            assertNull(error.getCause());
            assertFalse(invalid.toString().contains("private"));
        }
        assertFalse(settings.toString().contains("private"));
    }

    @ParameterizedTest
    @CsvSource({"jdbc:mysql://localhost/demo", "jdbc:oracle:thin:@//localhost:1521/demo",
            "jdbc:sqlserver://localhost;databaseName=demo", "jdbc:postgresql://localhost/demo",
            "jdbc:starrocks://localhost:9030/default_catalog.demo"})
    void createsAllRealDriverPoolsWithoutEagerConnectionsOrCredentialBearingHikariConfiguration(String url) {
        try (var target = factory.create(new DatabaseConnectionSettings(url, "user", "private-password", null))) {
            assertNull(target.pool.getJdbcUrl());
            assertNull(target.pool.getUsername());
            assertNull(target.pool.getPassword());
            assertEquals(0, target.pool.getMinimumIdle());
            assertFalse(target.pool.getDataSource().toString().contains("private"));
        }
    }

    @Test
    void validatesAnActualBorrowAndReturnsItsNamespace() throws SQLException {
        var pool = mock(HikariDataSource.class);
        var connection = mock(Connection.class);
        when(pool.getConnection()).thenReturn(connection);
        when(connection.isValid(1)).thenReturn(true);
        when(connection.getCatalog()).thenReturn("appdb");
        when(connection.getSchema()).thenReturn("public");
        var namespace = factory.validate(new DatabaseTarget(pool, new PostgreSqlDialect(), null));
        assertEquals(new DatabasePoolFactory.Namespace("appdb", "public"), namespace);
        verify(connection).close();
        when(connection.isValid(1)).thenReturn(false);
        assertEquals(VALIDATION_FAILED, assertThrows(DatabaseSwitchException.class,
                () -> factory.validate(new DatabaseTarget(pool, new PostgreSqlDialect(), null))).code());
    }

    @Test
    void sanitizesConnectAndConnectionValidationFailuresBeforeHikariCanLogThem() throws SQLException {
        Driver driver = mock(Driver.class);
        var credentials = new DatabaseConnectionSettings(settings.url(), "user", "private-password", null);
        var safe = new DatabasePoolFactory.CredentialSafeDataSource(driver, credentials, 1);
        when(driver.connect(anyString(), any())).thenThrow(new SQLException("private-host private-password", "28000", 1045));
        var error = assertThrows(SQLException.class, safe::getConnection);
        assertEquals("28000", error.getSQLState());
        assertEquals(1045, error.getErrorCode());
        assertFalse(error.toString().contains("private"));
        assertNull(error.getCause());

        var connection = mock(Connection.class);
        doReturn(connection).when(driver).connect(anyString(), any());
        when(connection.isValid(1)).thenThrow(new SQLException("private-password in validation", "08006"));
        var wrapped = safe.getConnection();
        assertFalse(wrapped.toString().contains("private"));
        var validationError = assertThrows(SQLException.class, () -> wrapped.isValid(1));
        assertFalse(validationError.toString().contains("private"));
        assertNull(validationError.getCause());
        wrapped.close();
        verify(connection).close();
    }

    @ParameterizedTest
    @CsvSource({"28000, 0", "28P01, 0", "99999, 1017", "28000, 18456"})
    void findsAuthenticationFailureInsidePoolTimeouts(String state, int vendor) throws SQLException {
        var pool = mock(HikariDataSource.class);
        var timeout = new SQLTimeoutException("pool timeout", "08001");
        timeout.initCause(new SQLException("private-password", state, vendor));
        when(pool.getConnection()).thenThrow(timeout);
        var error = assertThrows(DatabaseSwitchException.class,
                () -> factory.validate(new DatabaseTarget(pool, new MySqlDialect(), null)));
        assertEquals(AUTHENTICATION_FAILED, error.code());
        assertNull(error.getCause());
    }

    @Test
    void configuresDriverLoginTimeoutAndAllowsEmptyPasswords() throws SQLException {
        var driver = mock(Driver.class);
        when(driver.connect(anyString(), any())).thenReturn(mock(Connection.class));
        for (String url : List.of("jdbc:mysql://host/demo", "jdbc:oracle:thin:@//host/demo", "jdbc:sqlserver://host",
                "jdbc:postgresql://host/demo", "jdbc:starrocks://host/default_catalog.demo")) {
            new DatabasePoolFactory.CredentialSafeDataSource(driver,
                    new DatabaseConnectionSettings(url, "user", "", null), 2).getConnection().close();
        }
        verify(driver, times(5)).connect(anyString(), argThat((Properties properties) -> properties.getProperty("password").isEmpty()));
        verify(driver).connect(eq("jdbc:mysql://host/demo"), argThat(p -> p.getProperty("connectTimeout").equals("2000")));
        verify(driver).connect(eq("jdbc:postgresql://host/demo"), argThat(p -> p.getProperty("connectTimeout").equals("2")));
        verify(driver).connect(eq("jdbc:sqlserver://host"), argThat(p -> p.getProperty("loginTimeout").equals("2")));
    }
}
