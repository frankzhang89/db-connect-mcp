package com.example.dbconnectmcp.database;

import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.stereotype.Component;

@Component
public class StarRocksDialect implements DatabaseDialect {

    @Override
    public String name() { return "StarRocks"; }

    @Override
    public String urlPrefix() { return "jdbc:starrocks:"; }

    @Override
    public String driverClassName() { return "com.starrocks.cj.jdbc.Driver"; }

    @Override
    public String schemaPattern(Connection connection) throws SQLException {
        return connection.getSchema();
    }

    @Override
    public void prepareRead(Connection connection) {
        // Do not send MySQL SET SESSION TRANSACTION commands to StarRocks.
    }

    @Override
    public void prepareWrite(Connection connection) throws SQLException {
        connection.setAutoCommit(true);
    }

    @Override
    public boolean usesReadTransaction() {
        // Avoid depending on version-specific SQL transaction support for SELECT.
        return false;
    }

    @Override
    public boolean supportsGeneratedKeys() { return false; }
}
