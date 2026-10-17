package com.example.dbconnectmcp.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Properties;
import java.util.UUID;
import org.springframework.jdbc.datasource.AbstractDataSource;

import static com.example.dbconnectmcp.database.DatabaseSwitchException.Code.*;

/**
 * Builds unpublished JDBC targets, validates real connections, and enables pool
 * prefill after validation. Publication and target lifetime belong to the manager.
 */
public class DatabasePoolFactory {
    /** Safe metadata returned to clients without including the JDBC URL or credentials. */
    public record Namespace(String catalog, String schema) { }

    private final DatabaseProperties poolSettings;
    private final DatabaseSwitchProperties timeouts;
    private final DatabaseDialectRegistry registry;

    public DatabasePoolFactory(DatabaseProperties poolSettings, DatabaseSwitchProperties timeouts, DatabaseDialectRegistry registry) {
        this.poolSettings = poolSettings;
        this.timeouts = timeouts;
        this.registry = registry;
    }

    /** Creates an independent candidate; constructing a pool does not prove connectivity. */
    DatabaseTarget create(DatabaseConnectionSettings settings) {
        DatabaseDialect dialect;
        try {
            if (settings == null || settings.username() == null || settings.username().isBlank()
                    || settings.password() == null || settings.jdbcUrl() == null || settings.jdbcUrl().length() > 8192) {
                throw new IllegalArgumentException();
            }
            // Driver selection comes from supported URL prefixes rather than a
            // caller-supplied class name. Schema validation here checks input shape.
            dialect = registry.resolve(settings.jdbcUrl());
            if (settings.schema() != null && !settings.schema().isBlank()) {
                DatabaseMetadata.validateIdentifier(settings.schema());
            }
        }
        catch (IllegalArgumentException error) { throw new DatabaseSwitchException(INVALID_ARGUMENT); }

        try {
            Driver driver = (Driver) Class.forName(dialect.driverClassName()).getDeclaredConstructor().newInstance();
            HikariConfig config = new HikariConfig();
            // Never put JDBC URLs/credentials in HikariConfig: it logs its configuration.
            config.setDataSource(new CredentialSafeDataSource(driver, settings, timeouts.validationTimeoutSeconds()));
            config.setPoolName("db-mcp-" + UUID.randomUUID());
            config.setMaximumPoolSize(poolSettings.maxActive() == null ? 3 : poolSettings.maxActive());
            // Candidates must not prefill connections before successful validation.
            config.setMinimumIdle(0);
            config.setConnectionTimeout(poolSettings.connectionTimeout() == null ? 5000 : poolSettings.connectionTimeout());
            config.setValidationTimeout(timeouts.validationTimeoutSeconds() * 1000L);
            // Start without synchronous connectivity checks; validate() performs the
            // explicit check in the manager's cancellable validation task instead.
            config.setInitializationFailTimeout(-1);
            return new DatabaseTarget(new HikariDataSource(config), dialect, settings.schema());
        }
        catch (Exception error) { throw new DatabaseSwitchException(CONNECTION_FAILED); }
    }

    /**
     * Borrows a real connection and resolves its metadata namespace before publication.
     * A configured schema is normalized for metadata use; this does not issue SET SCHEMA
     * or prove that the schema exists or that every later query will be permitted.
     */
    Namespace validate(DatabaseTarget target) {
        try (Connection connection = target.pool.getConnection()) {
            if (!connection.isValid(timeouts.validationTimeoutSeconds())) {
                throw new DatabaseSwitchException(VALIDATION_FAILED);
            }
            return new Namespace(target.dialect.catalog(connection),
                    DatabaseMetadata.schema(connection, target.dialect, target.configuredSchema, null));
        }
        catch (SQLException error) {
            // Hikari may wrap authentication errors in a connection-acquisition timeout.
            SQLException next = error;
            for (int i = 0; i < 8 && next != null; i++) {
                var classified = DatabaseSwitchException.from(next);
                if (classified.code() == AUTHENTICATION_FAILED) { throw classified; }
                next = next.getCause() instanceof SQLException cause ? cause : next.getNextException();
            }
            throw DatabaseSwitchException.from(error);
        }
        catch (IllegalArgumentException error) { throw new DatabaseSwitchException(VALIDATION_FAILED); }
    }

    /** Restores the shared idle-size policy; it does not publish the target itself. */
    void activate(DatabaseTarget target) {
        int desired = poolSettings.minIdle() == null ? target.pool.getMaximumPoolSize() : poolSettings.minIdle();
        target.pool.setMinimumIdle(Math.min(desired, target.pool.getMaximumPoolSize()));
    }

    /** Sanitizes driver failures before Hikari can log exceptions or connection settings. */
    static final class CredentialSafeDataSource extends AbstractDataSource {
        private final Driver driver;
        private final DatabaseConnectionSettings settings;
        private final int loginSeconds;

        CredentialSafeDataSource(Driver driver, DatabaseConnectionSettings settings, int loginSeconds) {
            this.driver = driver;
            this.settings = settings;
            this.loginSeconds = loginSeconds;
        }

        @Override
        public Connection getConnection() throws SQLException {
            // Keep credentials in the driver call rather than HikariConfig, whose
            // diagnostic output may otherwise include connection configuration.
            Properties properties = new Properties();
            properties.setProperty("user", settings.username());
            properties.setProperty("password", settings.password());
            String url = settings.jdbcUrl();
            // Drivers use different names and units for their connection timeouts.
            // These complement Future cancellation when a driver blocks in network I/O.
            if (url.startsWith("jdbc:oracle:")) {
                properties.setProperty("oracle.net.CONNECT_TIMEOUT", Integer.toString(loginSeconds * 1000));
                properties.setProperty("oracle.net.OUTBOUND_CONNECT_TIMEOUT", Integer.toString(loginSeconds * 1000));
            }
            else if (url.startsWith("jdbc:sqlserver:")) {
                properties.setProperty("loginTimeout", Integer.toString(loginSeconds));
            }
            else if (url.startsWith("jdbc:postgresql:")) {
                properties.setProperty("connectTimeout", Integer.toString(loginSeconds));
                properties.setProperty("loginTimeout", Integer.toString(loginSeconds));
            }
            else { properties.setProperty("connectTimeout", Integer.toString(loginSeconds * 1000)); }
            try {
                Connection connection = driver.connect(url, properties);
                if (connection == null) { throw new SQLException("Unsupported JDBC address.", "08001"); }
                // Hikari also logs validation/setup failures from Connection methods.
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("toString")) { return "CredentialSafeConnection[redacted]"; }
                            if (method.getName().equals("hashCode")) { return System.identityHashCode(proxy); }
                            if (method.getName().equals("equals")) { return proxy == args[0]; }
                            try { return method.invoke(connection, args); }
                            catch (InvocationTargetException error) {
                                if (error.getCause() instanceof SQLException sql) { throw sanitized(sql); }
                                throw new SQLException("Database operation failed.", "08001");
                            }
                        });
            }
            catch (SQLException error) {
                // Keep only standardized classification fields, never a driver message/cause.
                throw sanitized(error);
            }
            catch (RuntimeException error) { throw new SQLException("Database connection failed.", "08001"); }
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLFeatureNotSupportedException("Per-call database credentials are not supported.");
        }

        @Override
        public void setLoginTimeout(int seconds) {
            // The per-driver timeout is fixed by the server's validation policy.
        }

        @Override
        public int getLoginTimeout() { return loginSeconds; }

        private static SQLException sanitized(SQLException error) {
            // Preserve only classification fields needed by DatabaseSwitchException;
            // original messages and causes may contain addresses or credentials.
            String state = error.getSQLState();
            if (state == null || !state.matches("[A-Z0-9]{5}")) { state = "08001"; }
            if (error instanceof SQLFeatureNotSupportedException) {
                return new SQLFeatureNotSupportedException("Database operation is not supported.", state, error.getErrorCode());
            }
            if (error instanceof SQLTimeoutException) {
                return new SQLTimeoutException("Database operation timed out.", state, error.getErrorCode());
            }
            return new SQLException("Database operation failed.", state, error.getErrorCode());
        }

        @Override
        public String toString() { return "CredentialSafeDataSource[redacted]"; }
    }
}
