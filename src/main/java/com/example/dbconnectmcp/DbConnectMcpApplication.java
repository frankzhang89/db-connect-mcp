package com.example.dbconnectmcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. Boots the MCP server on the embedded servlet container with
 * the Streamable HTTP transport; see application.yml for the endpoint.
 */
@SpringBootApplication
public class DbConnectMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(DbConnectMcpApplication.class, args);
    }
}
