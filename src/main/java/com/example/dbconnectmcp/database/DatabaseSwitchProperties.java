package com.example.dbconnectmcp.database;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Server-controlled switch limits, shared by initial startup and later replacements.
 * The total budget includes switch-lock waiting and validation; the smaller validation
 * limit also configures JDBC login/connect timeouts and connection.isValid().
 */
@ConfigurationProperties("dbmcp.switch")
public record DatabaseSwitchProperties(@DefaultValue("15") int timeoutSeconds,
        @DefaultValue("5") int validationTimeoutSeconds) {

    public DatabaseSwitchProperties {
        if (timeoutSeconds < 1 || timeoutSeconds > 300 || validationTimeoutSeconds < 1
                || validationTimeoutSeconds > timeoutSeconds) {
            throw new IllegalArgumentException("Invalid database switch timeouts; require 1 <= validation <= total <= 300 seconds.");
        }
    }
}
