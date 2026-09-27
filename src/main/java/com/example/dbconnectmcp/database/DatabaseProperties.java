package com.example.dbconnectmcp.database;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dbmcp.database")
public record DatabaseProperties(String url, String username, String password) {
}
