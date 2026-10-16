package com.example.dbconnectmcp.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the HikariCP pool shared by all database tools. The pool itself is
 * not marked read-only: dialect policies prepare read operations and restore
 * writable sessions (see
 * {@link DatabaseReadService} and {@link DatabaseWriteService}).
 */
@Configuration
@EnableConfigurationProperties(DatabaseProperties.class)
public class DatabaseConfiguration {

    @Bean
    public DataSource dataSource(DatabaseProperties properties, DatabaseDialectRegistry registry) {
        if (properties.username() == null || properties.username().isBlank() || properties.password() == null) {
            throw new IllegalArgumentException("Missing database username or password; configure them in your private YAML.");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.url());
        config.setUsername(properties.username());
        config.setPassword(properties.password());
        // Resolve the JDBC driver class from the URL prefix via the dialect registry.
        config.setDriverClassName(registry.resolve(properties.url()).driverClassName());
        config.setPoolName("db-connect-mcp");
        // Default sizing; overridden by the optional dbmcp.database.max-active / min-idle values.
        config.setMaximumPoolSize(3);
        if (properties.maxActive() != null && properties.maxActive() > 0) {
            config.setMaximumPoolSize(properties.maxActive());
        }
        if (properties.minIdle() != null && properties.minIdle() >= 0) {
            config.setMinimumIdle(properties.minIdle());
        }
        // Milliseconds to wait for a free connection before failing.
        if (properties.connectionTimeout() != null && properties.connectionTimeout() > 0) {
            config.setConnectionTimeout(properties.connectionTimeout());
        }
        // Start even when the database is unreachable; failures surface on first use.
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }
}
