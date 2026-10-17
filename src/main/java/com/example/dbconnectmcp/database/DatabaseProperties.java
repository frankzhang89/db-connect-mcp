package com.example.dbconnectmcp.database;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for a single JDBC database. The same account and
 * pool serve both read and write tools. The optional {@code max-active},
 * {@code min-idle}, and {@code connection-timeout} values map to the
 * corresponding HikariCP settings.
 * Address, credentials, and schema seed the initial target at startup. Runtime
 * switches replace those values in memory while reusing this pool-sizing policy.
 */
@ConfigurationProperties(prefix = "dbmcp.database")
public record DatabaseProperties(
        String url,
        String username,
        String password,
        Integer maxActive,
        Integer minIdle,
        Long connectionTimeout,
        String schema) {

    @Override
    public String toString() { return "DatabaseProperties[connection=redacted]"; }
}
