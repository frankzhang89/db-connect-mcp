package com.example.dbconnectmcp.database;

import org.springframework.stereotype.Component;

/**
 * MySQL adapter. MySQL uses the catalog for its database namespace.
 * The default session policy handles JDBC read-only hints and auto-commit writes.
 */
@Component
public class MySqlDialect implements DatabaseDialect {

    @Override
    public String name() {
        return "MySQL";
    }

    @Override
    public String urlPrefix() {
        return "jdbc:mysql:";
    }

    @Override
    public String driverClassName() {
        return "com.mysql.cj.jdbc.Driver";
    }
}
