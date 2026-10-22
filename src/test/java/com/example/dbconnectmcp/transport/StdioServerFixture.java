package com.example.dbconnectmcp.transport;

import com.example.dbconnectmcp.DbConnectMcpApplication;
import com.example.dbconnectmcp.auth.BearerTokenVerifier;
import com.example.dbconnectmcp.database.DatabaseDialect;
import com.example.dbconnectmcp.database.PostgreSqlDialect;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextClosedEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.CountDownLatch;

/** Child-process entry point: real application with a test-only H2 adapter. */
public class StdioServerFixture {
    public static void main(String[] args) throws Exception {
        var application = new SpringApplication(DbConnectMcpApplication.class, FixtureConfiguration.class);
        application.setBannerMode(Banner.Mode.OFF);
        var closed = new CountDownLatch(1);
        application.addListeners((ApplicationListener<ContextClosedEvent>) event -> closed.countDown());
        var context = application.run(args);
        closed.await();
        // close() serializes with the lifecycle thread, so resource destruction is finished before observing H2.
        context.close();
        String observation = context.getEnvironment().getProperty("test.observed-balance-file");
        if (observation != null) {
            try (var connection = DriverManager.getConnection(
                    context.getEnvironment().getRequiredProperty("dbmcp.database.url"), "sa", "");
                    var statement = connection.createStatement();
                    var result = statement.executeQuery("SELECT balance FROM account WHERE id=1")) {
                result.next();
                Files.writeString(Path.of(observation), Integer.toString(result.getInt(1)));
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean
        DatabaseDialect h2Dialect() {
            return new PostgreSqlDialect() {
                @Override public String name() { return "H2 test database"; }
                @Override public String urlPrefix() { return "jdbc:h2:"; }
                @Override public String driverClassName() { return "org.h2.Driver"; }
                @Override public boolean transactionalTruncate() { return false; }
            };
        }

        @Bean
        ApplicationListener<ApplicationReadyEvent> verifyStdioContext() {
            return event -> {
                var context = event.getApplicationContext();
                if (context instanceof WebServerApplicationContext
                        || context.containsBean("mcpBearerFilter") || context.containsBean("transactionSessionFilter")
                        || !context.getBean(BearerTokenVerifier.class).accepts(null)) {
                    throw new IllegalStateException("Stdio must have no web context or HTTP authentication");
                }
                LoggerFactory.getLogger(StdioServerFixture.class).info("Stdio fixture ready");
                LoggerFactory.getLogger(StdioServerFixture.class).debug("Stdio fixture debug enabled");
            };
        }
    }
}
