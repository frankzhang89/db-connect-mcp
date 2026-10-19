package com.example.dbconnectmcp.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.stereotype.Component;

/** Oracle uses schema metadata and an explicit server-side read-only transaction. */
@Component
public class OracleDialect implements DatabaseDialect {

    @Override
    public void beginTransaction(Connection connection) throws SQLException {
        // Oracle does not use JDBC's read-only hint; auto-commit controls the transaction.
        if (!connection.getMetaData().supportsTransactions()) {
            throw new SQLException("Explicit transactions are not supported.", "0A000");
        }
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        connection.setAutoCommit(false);
    }

    @Override
    public String name() { return "Oracle"; }

    @Override
    public String urlPrefix() { return "jdbc:oracle:"; }

    @Override
    public String driverClassName() { return "oracle.jdbc.OracleDriver"; }

    @Override
    public String catalog(Connection connection) { return null; }

    @Override
    public String schemaPattern(Connection connection) throws SQLException {
        return connection.getSchema();
    }

    @Override
    public void prepareRead(Connection connection) {
        // Oracle's setReadOnly is not a server-enforced read-only transaction.
    }

    @Override
    public void beginRead(Connection connection) throws SQLException {
        // Start a new transaction before issuing Oracle's transaction-level read policy.
        // DatabaseReadService rolls it back before returning the pooled connection.
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET TRANSACTION READ ONLY");
        }
    }

    @Override
    public void prepareWrite(Connection connection) throws SQLException {
        connection.setAutoCommit(true);
    }

    @Override
    public boolean supportsGeneratedKeys() {
        // RETURN_GENERATED_KEYS without column names returns ROWID, not the PK.
        return false;
    }
}
