package com.example.dbconnectmcp;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * Entry point. Boots Streamable HTTP or stdio according to application.yml.
 * JDBC auto-configuration is excluded because DatabaseRuntimeManager owns dynamic
 * pools rather than a single Spring-managed, permanently configured DataSource.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class DbConnectMcpApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(DbConnectMcpApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        app.run(args);
    }
}
