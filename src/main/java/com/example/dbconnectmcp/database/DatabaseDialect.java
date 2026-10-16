package com.example.dbconnectmcp.database;

import java.sql.Connection;
import java.sql.SQLException;

/** Add another implementation when a JDBC database is supported. */
public interface DatabaseDialect {

    String name();

    String urlPrefix();

    String driverClassName();

    default String catalog(Connection connection) throws SQLException {
        return connection.getCatalog();
    }

    default String schemaPattern(Connection connection) throws SQLException {
        return null;
    }

    /** JDBC read-only is a hint; some databases require an explicit transaction. */
    default void prepareRead(Connection connection) throws SQLException {
        connection.setReadOnly(true);
    }

    default boolean usesReadTransaction() {
        return true;
    }

    default void beginRead(Connection connection) throws SQLException {
        prepareRead(connection);
        if (usesReadTransaction()) {
            connection.setAutoCommit(false);
        }
    }

    default void prepareWrite(Connection connection) throws SQLException {
        connection.setReadOnly(false);
        connection.setAutoCommit(true);
    }

    /** Only request generated keys for plain INSERTs on supported drivers. */
    default boolean supportsGeneratedKeys() {
        return true;
    }
}
