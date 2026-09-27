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
}
