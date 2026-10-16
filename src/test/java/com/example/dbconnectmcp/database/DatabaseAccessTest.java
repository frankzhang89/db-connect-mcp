package com.example.dbconnectmcp.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DatabaseAccessTest {

    private final DatabaseDialectRegistry registry = new DatabaseDialectRegistry(List.of(new MySqlDialect(),
            new OracleDialect(), new SqlServerDialect(), new PostgreSqlDialect(), new StarRocksDialect()));
    private DataSource source;
    private Connection connection;
    private DatabaseMetaData metadata;

    @BeforeEach
    void setUp() throws SQLException {
        source = mock(DataSource.class);
        connection = mock(Connection.class);
        metadata = mock(DatabaseMetaData.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("demo");
        when(connection.getSchema()).thenReturn("app");
        when(metadata.getSearchStringEscape()).thenReturn("\\");
    }

    private DatabaseProperties properties(String url, String schema) {
        return new DatabaseProperties(url, "user", "unused", null, null, null, schema);
    }

    private DatabaseReadService reads(String url, String schema) {
        return new DatabaseReadService(source, properties(url, schema), registry, new ReadOnlySqlGuard(), 2, 10, 50);
    }

    private DatabaseWriteService writes(String url) {
        return new DatabaseWriteService(source, new WriteSqlGuard(), properties(url, null), registry, true, 10);
    }

    @ParameterizedTest
    @CsvSource({
            "jdbc:mysql://localhost/demo, true, true",
            "jdbc:oracle:thin:@//localhost:1521/demo, true, false",
            "jdbc:sqlserver://localhost;databaseName=demo, true, true",
            "jdbc:postgresql://localhost/demo, true, true",
            "jdbc:starrocks://localhost:9030/default_catalog.demo, false, false"
    })
    void queriesBindParametersLimitRowsAndUseDialectReadPolicy(String url, boolean rollback, boolean readOnlyHint)
            throws SQLException {
        String sql = "SELECT id FROM users WHERE id > ?";
        PreparedStatement statement = mock(PreparedStatement.class);
        Statement transaction = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        ResultSetMetaData columns = mock(ResultSetMetaData.class);
        when(connection.createStatement()).thenReturn(transaction);
        when(connection.prepareStatement(sql)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(columns);
        when(columns.getColumnCount()).thenReturn(1);
        when(columns.getColumnLabel(1)).thenReturn("id");
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getObject(1)).thenReturn(7);

        Map<String, Object> result = reads(url, null).query(sql, List.of(3), 1);

        assertEquals(List.of(Map.of("id", 7)), result.get("rows"));
        assertEquals(true, result.get("truncated"));
        verify(statement).setObject(1, 3);
        verify(statement).setMaxRows(2);
        verify(statement).setQueryTimeout(10);
        verify(connection, times(rollback ? 1 : 0)).rollback();
        verify(connection, times(readOnlyHint ? 1 : 0)).setReadOnly(true);
        verify(connection, times(rollback ? 1 : 0)).setAutoCommit(false);
        if (url.startsWith("jdbc:oracle:")) {
            var order = inOrder(connection, transaction, statement);
            order.verify(connection).setAutoCommit(false);
            order.verify(transaction).execute("SET TRANSACTION READ ONLY");
            order.verify(statement).executeQuery();
        }
    }

    @Test
    void queryFailureRollsBackAndDoesNotExposeCredentials() throws SQLException {
        String sql = "SELECT id FROM users";
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(sql)).thenReturn(statement);
        when(statement.executeQuery()).thenThrow(new SQLException("private-host password=secret", "42000"));
        var failure = assertThrows(IllegalStateException.class,
                () -> reads("jdbc:postgresql://localhost/demo", null).query(sql, null, null));
        assertEquals("Database read failed (SQLState: 42000).", failure.getMessage());
        verify(connection).rollback();
    }

    @ParameterizedTest
    @CsvSource({
            "jdbc:oracle:thin:@//localhost:1521/demo, orders, ORDERS, APP, true, false",
            "jdbc:postgresql://localhost/demo, ORDERS, orders, app, false, true",
            "jdbc:postgresql://localhost/demo, '\"Mixed_Case\"', Mixed_Case, app, false, true"
    })
    void describesIdentifiersUsingDatabaseCaseRules(String url, String supplied, String actual, String schema,
            boolean upper, boolean lower) throws SQLException {
        when(metadata.storesUpperCaseIdentifiers()).thenReturn(upper);
        when(metadata.storesLowerCaseIdentifiers()).thenReturn(lower);
        ResultSet columns = mock(ResultSet.class);
        ResultSet primaryKeys = mock(ResultSet.class);
        String catalog = url.startsWith("jdbc:oracle:") ? null : "demo";
        when(metadata.getColumns(catalog, schema, actual.replace("_", "\\_"), "%")).thenReturn(columns);
        when(metadata.getPrimaryKeys(catalog, schema, actual)).thenReturn(primaryKeys);
        when(columns.next()).thenReturn(true, false);
        when(columns.getString("TABLE_NAME")).thenReturn(actual);
        when(columns.getString("COLUMN_NAME")).thenReturn("id");
        when(columns.getString("TYPE_NAME")).thenReturn("INTEGER");
        when(primaryKeys.next()).thenReturn(true, false);
        when(primaryKeys.getString("COLUMN_NAME")).thenReturn("id");

        Map<String, Object> result = reads(url, schema).describeTable(supplied);

        assertEquals(List.of("id"), result.get("primaryKeys"));
        assertEquals(1, ((List<?>) result.get("columns")).size());
        verify(metadata).getPrimaryKeys(catalog, schema, actual);
    }

    @Test
    void listsOnlyConfiguredSchemaWithLiteralWildcardsAndReturnsNamespace() throws SQLException {
        ResultSet tables = mock(ResultSet.class);
        when(metadata.getTables(eq("demo"), eq("app\\_data"), eq("%"), any(String[].class))).thenReturn(tables);
        when(tables.next()).thenReturn(true, false);
        when(tables.getString("TABLE_NAME")).thenReturn("events");
        when(tables.getString("TABLE_TYPE")).thenReturn("TABLE");
        when(tables.getString("TABLE_CAT")).thenReturn("demo");
        when(tables.getString("TABLE_SCHEM")).thenReturn("app_data");

        Map<String, Object> result = reads("jdbc:postgresql://localhost/demo", "app_data").listTables();

        assertEquals(List.of(Map.of("name", "events", "type", "TABLE", "catalog", "demo", "schema", "app_data")),
                result.get("tables"));
    }

    @Test
    void starRocksUsesSeparateCatalogAndDatabaseSchema() throws SQLException {
        when(connection.getCatalog()).thenReturn("default_catalog");
        when(connection.getSchema()).thenReturn("demo");
        ResultSet tables = mock(ResultSet.class);
        when(metadata.getTables(eq("default_catalog"), eq("demo"), eq("%"), any(String[].class))).thenReturn(tables);
        assertEquals("StarRocks", reads("jdbc:starrocks://localhost:9030/default_catalog.demo", null)
                .listTables().get("databaseType"));
        verify(connection, never()).setReadOnly(true);
    }

    @Test
    void refusesUnscopedMetadataWhenNoCurrentSchemaExists() throws SQLException {
        when(connection.getSchema()).thenReturn(null);
        var failure = assertThrows(IllegalArgumentException.class,
                () -> reads("jdbc:postgresql://localhost/demo", null).listTables());
        assertTrue(failure.getMessage().contains("dbmcp.database.schema"));
        verify(metadata, never()).getTables(any(), any(), any(), any());
    }

    @Test
    void sqlServerHonorsSchemaOverrideAndCaseInsensitiveNames() throws SQLException {
        when(metadata.storesMixedCaseIdentifiers()).thenReturn(true);
        when(metadata.supportsMixedCaseIdentifiers()).thenReturn(false);
        ResultSet columns = mock(ResultSet.class);
        ResultSet keys = mock(ResultSet.class);
        when(metadata.getColumns("demo", "audit", "users", "%")).thenReturn(columns);
        when(metadata.getPrimaryKeys("demo", "audit", "Users")).thenReturn(keys);
        when(columns.next()).thenReturn(true, false);
        when(columns.getString("TABLE_NAME")).thenReturn("Users");
        when(columns.getString("COLUMN_NAME")).thenReturn("id");
        when(columns.getString("TYPE_NAME")).thenReturn("int");
        Map<String, Object> result = reads("jdbc:sqlserver://localhost;databaseName=demo", "dbo")
                .describeTable("users", "audit");
        assertEquals("Users", result.get("table"));
        assertEquals("audit", result.get("schema"));
        verify(metadata).getPrimaryKeys("demo", "audit", "Users");
    }

    @ParameterizedTest
    @CsvSource({
            "jdbc:mysql://localhost/demo, true",
            "jdbc:oracle:thin:@//localhost:1521/demo, false",
            "jdbc:sqlserver://localhost;databaseName=demo, true",
            "jdbc:postgresql://localhost/demo, false",
            "jdbc:starrocks://localhost:9030/default_catalog.demo, false"
    })
    void insertsRequestGeneratedKeysOnlyWhereTheyRepresentIds(String url, boolean returnKeys) throws SQLException {
        String sql = "INSERT INTO users (name) VALUES (?)";
        PreparedStatement statement = mock(PreparedStatement.class);
        if (returnKeys) {
            when(connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)).thenReturn(statement);
            ResultSet keys = mock(ResultSet.class);
            when(statement.getGeneratedKeys()).thenReturn(keys);
            when(keys.next()).thenReturn(true, false);
            when(keys.getObject(1)).thenReturn(42L);
        }
        else {
            when(connection.prepareStatement(sql)).thenReturn(statement);
        }
        when(statement.executeUpdate()).thenReturn(1);

        Map<String, Object> result = writes(url).executeDml(sql, List.of("Alice"));

        assertEquals(1, result.get("affectedRows"));
        assertEquals(returnKeys, result.containsKey("generatedKeys"));
        if (returnKeys) {
            assertEquals(List.of(42L), result.get("generatedKeys"));
        }
        verify(statement).setObject(1, "Alice");
        verify(statement, times(returnKeys ? 1 : 0)).getGeneratedKeys();
        verify(connection).setAutoCommit(true);
    }

    @Test
    void updatesDoNotRequestGeneratedKeysAndDdlExplicitlyAutocommits() throws SQLException {
        String sql = "UPDATE users SET name = ? WHERE id = ?";
        PreparedStatement update = mock(PreparedStatement.class);
        when(connection.prepareStatement(sql)).thenReturn(update);
        writes("jdbc:mysql://localhost/demo").executeDml(sql, List.of("Alice", 1));
        verify(update, never()).getGeneratedKeys();

        Statement ddl = mock(Statement.class);
        when(connection.createStatement()).thenReturn(ddl);
        assertEquals(Map.of("success", true), writes("jdbc:postgresql://localhost/demo")
                .executeDdl("CREATE TABLE example (id INTEGER)"));
        verify(connection, times(2)).setAutoCommit(true);
        verify(ddl).execute("CREATE TABLE example (id INTEGER)");
    }
}
