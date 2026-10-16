package com.example.dbconnectmcp.database;

import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.stereotype.Component;

@Component
public class PostgreSqlDialect implements DatabaseDialect {

    @Override
    public String name() { return "PostgreSQL"; }

    @Override
    public String urlPrefix() { return "jdbc:postgresql:"; }

    @Override
    public String driverClassName() { return "org.postgresql.Driver"; }

    @Override
    public String schemaPattern(Connection connection) throws SQLException {
        return connection.getSchema();
    }

    @Override
    public boolean supportsGeneratedKeys() {
        // The default PostgreSQL RETURNING * is not necessarily a generated PK.
        return false;
    }
}
