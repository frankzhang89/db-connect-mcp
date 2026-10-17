package com.example.dbconnectmcp.database;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Database-specific driver, namespace, and session policy stored with each target.
 * Add an implementation to support another JDBC database; the URL registry selects
 * it when a target is created, and leases retain it across subsequent switches.
 */
public interface DatabaseDialect {

    /** Human-readable database type returned with target metadata. */
    String name();

    /** Supported JDBC prefix used to resolve this policy without opening a connection. */
    String urlPrefix();

    /** Driver loaded for a new target; callers cannot supply arbitrary driver classes. */
    String driverClassName();

    /** Catalog filter for JDBC metadata; some databases use schemas instead. */
    default String catalog(Connection connection) throws SQLException {
        return connection.getCatalog();
    }

    /** Default metadata schema; null is appropriate for MySQL's catalog namespace. */
    default String schemaPattern(Connection connection) throws SQLException {
        return null;
    }

    /** JDBC read-only is a hint; some databases require an explicit transaction. */
    default void prepareRead(Connection connection) throws SQLException {
        connection.setReadOnly(true);
    }

    /** Whether read queries need a transaction that the service rolls back afterward. */
    default boolean usesReadTransaction() {
        return true;
    }

    /** Prepares a pooled connection for a query using this database's read policy. */
    default void beginRead(Connection connection) throws SQLException {
        prepareRead(connection);
        if (usesReadTransaction()) {
            connection.setAutoCommit(false);
        }
    }

    /** Restores a writable auto-commit session for one DML or DDL operation. */
    default void prepareWrite(Connection connection) throws SQLException {
        connection.setReadOnly(false);
        connection.setAutoCommit(true);
    }

    /** Only request generated keys for plain INSERTs on supported drivers. */
    default boolean supportsGeneratedKeys() {
        return true;
    }
}
