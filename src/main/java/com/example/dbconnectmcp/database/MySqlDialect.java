package com.example.dbconnectmcp.database;

import org.springframework.stereotype.Component;

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
