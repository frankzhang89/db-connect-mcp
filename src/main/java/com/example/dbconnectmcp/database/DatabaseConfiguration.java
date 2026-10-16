package com.example.dbconnectmcp.database;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * Starts the shared manager only after authentication configuration is valid.
 * A successful initial database validation is required before MCP becomes ready.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DatabaseProperties.class, DatabaseSwitchProperties.class})
public class DatabaseConfiguration {

    @Bean
    public DatabasePoolFactory databasePoolFactory(DatabaseProperties properties, DatabaseSwitchProperties timeouts,
            DatabaseDialectRegistry registry) {
        return new DatabasePoolFactory(properties, timeouts, registry);
    }

    @Bean(destroyMethod = "close")
    @DependsOn("mcpBearerVerifier")
    public DatabaseRuntimeManager databaseRuntimeManager(DatabasePoolFactory factory, DatabaseSwitchProperties timeouts,
            DatabaseProperties properties) {
        var manager = new DatabaseRuntimeManager(factory, timeouts);
        try {
            manager.switchTo(new DatabaseConnectionSettings(properties.url(), properties.username(), properties.password(), properties.schema()));
            return manager;
        }
        catch (RuntimeException error) {
            manager.close();
            throw error;
        }
    }
}
