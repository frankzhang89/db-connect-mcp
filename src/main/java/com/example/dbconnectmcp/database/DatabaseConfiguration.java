package com.example.dbconnectmcp.database;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.Ordered;

/**
 * Starts the shared manager only after authentication configuration is valid.
 * A successful initial database validation is required before MCP becomes ready.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DatabaseProperties.class, DatabaseSwitchProperties.class, DatabaseTransactionProperties.class})
public class DatabaseConfiguration {

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public FilterRegistrationBean<TransactionSessionFilter> transactionSessionFilter(DatabaseTransactionManager transactions,
            @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String endpoint) {
        var registration = new FilterRegistrationBean<>(new TransactionSessionFilter(transactions, endpoint));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    @Bean(destroyMethod = "close")
    public DatabaseTransactionManager databaseTransactionManager(DatabaseRuntimeManager runtime,
            DatabaseTransactionProperties limits,
            @Value("${dbmcp.query.timeout-seconds:10}") int statementTimeout,
            @Value("${dbmcp.query.max-rows:100}") int maxRows,
            @Value("${dbmcp.query.max-cell-chars:4000}") int maxCellChars) {
        // Spring destroys this dependent bean before its runtime manager, rolling back
        // retained transactions before their data source pools are shut down.
        return new DatabaseTransactionManager(runtime, limits, statementTimeout, maxRows, maxCellChars);
    }

    @Bean
    public DatabasePoolFactory databasePoolFactory(DatabaseProperties properties, DatabaseSwitchProperties timeouts,
            DatabaseDialectRegistry registry) {
        return new DatabasePoolFactory(properties, timeouts, registry);
    }

    @Bean(destroyMethod = "close")
    // Reject invalid authentication configuration before attempting database access.
    @DependsOn("mcpBearerVerifier")
    public DatabaseRuntimeManager databaseRuntimeManager(DatabasePoolFactory factory, DatabaseSwitchProperties timeouts,
            DatabaseProperties properties) {
        var manager = new DatabaseRuntimeManager(factory, timeouts);
        try {
            // Startup follows the same validation/publication path as runtime switches.
            // The singleton is not exposed until the configured initial target works.
            manager.switchTo(new DatabaseConnectionSettings(properties.url(), properties.username(), properties.password(), properties.schema()));
            return manager;
        }
        catch (RuntimeException error) {
            // A failed bean initialization must not leave validation workers or pools.
            manager.close();
            throw error;
        }
    }
}
