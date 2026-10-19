package com.example.dbconnectmcp.database;

import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.stereotype.Component;

/**
 * Uses the native StarRocks driver without relying on MySQL session transaction SQL
 * or generated-key support. SQL guards and account permissions still constrain access.
 */
@Component
public class StarRocksDialect implements DatabaseDialect {

    // Do not silently turn the transactional tool into version-dependent auto-commit writes.
    @Override
    public boolean supportsExplicitTransactions() { return false; }

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
