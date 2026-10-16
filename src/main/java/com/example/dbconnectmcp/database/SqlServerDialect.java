package com.example.dbconnectmcp.database;

import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.stereotype.Component;

@Component
public class SqlServerDialect implements DatabaseDialect {

    @Override
    public String name() { return "SQL Server"; }

    @Override
    public String urlPrefix() { return "jdbc:sqlserver:"; }

    @Override
    public String driverClassName() { return "com.microsoft.sqlserver.jdbc.SQLServerDriver"; }

    @Override
    public String schemaPattern(Connection connection) throws SQLException {
        return connection.getSchema();
    }
}
