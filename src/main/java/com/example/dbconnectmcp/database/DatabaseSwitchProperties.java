package com.example.dbconnectmcp.database;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

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
