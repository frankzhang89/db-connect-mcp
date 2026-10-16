package com.example.dbconnectmcp.database;

/** Credentials are deliberately excluded from diagnostic representations. */
public record DatabaseConnectionSettings(String jdbcUrl, String username, String password, String schema) {

    @Override
    public String toString() {
        return "DatabaseConnectionSettings[redacted]";
    }
}
