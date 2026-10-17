package com.example.dbconnectmcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * Entry point. Boots the MCP server on the embedded servlet container with
 * the Streamable HTTP transport; see application.yml for the endpoint.
 * JDBC auto-configuration is excluded because DatabaseRuntimeManager owns dynamic
 * pools rather than a single Spring-managed, permanently configured DataSource.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class DbConnectMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(DbConnectMcpApplication.class, args);
    }
}
