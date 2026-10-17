package com.example.dbconnectmcp.database;

/**
 * Immutable input for one JDBC target, used for both startup and runtime switches.
 * The record carries configuration only; DatabasePoolFactory validates it and builds
 * the pool. The optional schema scopes metadata discovery, not SQL session routing.
 * Accessors retain the original values, while diagnostic output hides credentials.
 *
 * @param jdbcUrl JDBC address used to select the dialect and open connections
 * @param username database account; validated as nonblank by the pool factory
 * @param password database password; empty is allowed, null is rejected by the factory
 * @param schema optional metadata schema; omission uses the new connection's default
 */
public record DatabaseConnectionSettings(String jdbcUrl, String username, String password, String schema) {

    @Override
    public String toString() {
        // A record's generated toString() would expose all components, including secrets.
        return "DatabaseConnectionSettings[redacted]";
    }
}
