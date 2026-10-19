package com.example.dbconnectmcp.database;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Resource limits for connections retained between agent calls. All durations use seconds. */
@ConfigurationProperties("dbmcp.transaction")
public record DatabaseTransactionProperties(
        @DefaultValue("120") int idleTimeoutSeconds,
        @DefaultValue("600") int maxLifetimeSeconds,
        @DefaultValue("5") int maxActive,
        @DefaultValue("20") int maxStatements,
        @DefaultValue("20000") int maxSqlChars,
        @DefaultValue("30") int scriptTimeoutSeconds) {

    public DatabaseTransactionProperties {
        if (idleTimeoutSeconds < 1 || maxLifetimeSeconds < idleTimeoutSeconds || maxActive < 1
                || maxStatements < 1 || maxStatements > 100 || maxSqlChars < 1 || maxSqlChars > 1_000_000
                || scriptTimeoutSeconds < 1) {
            throw new IllegalArgumentException("Invalid database transaction limits.");
        }
    }
}
