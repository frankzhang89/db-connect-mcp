package com.example.dbconnectmcp.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class TableDetailsTest {
    @Test
    void readsRealCommentsDefaultsIdentityAndOrderedCompositeKeysWithoutWritingUserData() throws Exception {
        String url = "jdbc:h2:mem:metadata_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        try (Connection setup = DriverManager.getConnection(url); Statement sql = setup.createStatement()) {
            sql.execute("CREATE TABLE parent(z INT, a INT, CONSTRAINT pk_parent PRIMARY KEY(z,a))");
            sql.execute("CREATE TABLE child(id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, z INT, a INT, "
                    + "label VARCHAR(50) DEFAULT 'new', CONSTRAINT uq_child UNIQUE(z,a), "
                    + "CONSTRAINT fk_child FOREIGN KEY(z,a) REFERENCES parent(z,a) ON DELETE CASCADE)");
            sql.execute("COMMENT ON TABLE child IS 'Child records'");
            sql.execute("COMMENT ON COLUMN child.label IS 'Display label'");
            sql.execute("CREATE INDEX ix_label ON child(label DESC, a ASC)");
            sql.execute("CREATE UNIQUE INDEX ix_standalone ON child(label)");
            sql.execute("CREATE VIEW child_view AS SELECT label FROM child");
            sql.execute("COMMENT ON VIEW child_view IS 'View comment'");

            var described = service(DriverManager.getConnection(url)).describeTable("CHILD");
            assertEquals("Child records", described.get("comment"));
            List<Map<String, Object>> columns = maps(described.get("columns"));
            Map<String, Object> label = named(columns, "LABEL");
            assertEquals("Display label", label.get("comment"));
            assertEquals("'new'", label.get("defaultValue"));
            assertEquals(false, label.get("autoIncrement"));
            assertNull(label.get("identity")); // Generic JDBC cannot distinguish serial from identity.
            assertEquals(true, named(columns, "ID").get("autoIncrement"));
            assertFalse(described.containsKey("sequence"));
            assertFalse(label.containsKey("sequence"));
            var index = named(maps(described.get("indexes")), "IX_LABEL");
            assertEquals(false, index.get("unique"));
            assertEquals(List.of("LABEL", "A"), maps(index.get("columns")).stream().map(c -> c.get("name")).toList());
            assertEquals(List.of("DESC", "ASC"), maps(index.get("columns")).stream().map(c -> c.get("sortDirection")).toList());
            assertEquals(List.of(Map.of("name", "UQ_CHILD", "columns", List.of("Z", "A"))), described.get("uniqueConstraints"));
            assertTrue((boolean) named(maps(described.get("indexes")), "IX_STANDALONE").get("unique"));
            Map<String, Object> fk = maps(described.get("foreignKeys")).getFirst();
            assertEquals("FK_CHILD", fk.get("name"));
            assertEquals(List.of("Z", "A"), fk.get("columns"));
            assertEquals(List.of("Z", "A"), fk.get("referencedColumns"));
            assertEquals("PARENT", fk.get("referencedTable"));
            assertEquals("CASCADE", fk.get("deleteRule"));
            assertEquals(List.of("Z", "A"), service(DriverManager.getConnection(url)).describeTable("PARENT").get("primaryKeys"));
            List<Map<String, Object>> tables = maps(service(DriverManager.getConnection(url)).listTables().get("tables"));
            assertEquals("Child records", named(tables, "CHILD").get("comment"));
            assertEquals("View comment", named(tables, "CHILD_VIEW").get("comment"));
            assertNull(named(tables, "PARENT").get("comment"));
            try (ResultSet rows = sql.executeQuery("SELECT COUNT(*) FROM child")) {
                rows.next(); assertEquals(0, rows.getInt(1));
            }
        }
    }

    @Test
    void unsupportedStarRocksMetadataIsUnknownRatherThanAnEmptyInventory() throws Exception {
        Connection connection = mock(Connection.class);
        TableDetails details = new TableDetails(connection, new StarRocksDialect(), 10);
        assertNull(details.indexes("default_catalog", "demo", "events"));
        assertNull(details.foreignKeys("default_catalog", "demo", "events"));
        assertNull(details.uniqueConstraints("default_catalog", "demo", "events"));
        verifyNoInteractions(connection);
    }

    @Test
    void foreignKeyColumnsAreSortedByPositionAndSeparateConstraintsStaySeparate() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getImportedKeys("demo", "app", "child")).thenReturn(rows);
        when(rows.next()).thenReturn(true, true, true, false);
        when(rows.getString("FK_NAME")).thenReturn("fk_one", "fk_one", "fk_two");
        when(rows.getInt("KEY_SEQ")).thenReturn(2, 1, 1);
        when(rows.getString("FKCOLUMN_NAME")).thenReturn("a", "z", "other");
        when(rows.getString("PKCOLUMN_NAME")).thenReturn("a", "z", "id");
        when(rows.getString("PKTABLE_NAME")).thenReturn("parent");
        when(rows.getInt("UPDATE_RULE")).thenReturn(DatabaseMetaData.importedKeyNoAction);
        when(rows.getInt("DELETE_RULE")).thenReturn(DatabaseMetaData.importedKeySetNull);
        var keys = new TableDetails(connection, new PostgreSqlDialect(), 10).foreignKeys("demo", "app", "child");
        assertEquals(2, keys.size());
        assertEquals(List.of("z", "a"), keys.getFirst().get("columns"));
        assertEquals(List.of("z", "a"), keys.getFirst().get("referencedColumns"));
        assertEquals("NO_ACTION", keys.getFirst().get("updateRule"));
        assertEquals("SET_NULL", keys.getFirst().get("deleteRule"));
        verify(rows).close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"oracle", "sqlserver", "postgresql"})
    void readsVendorCommentsAndIdentityWithBoundIdentifiers(String type) throws Exception {
        DatabaseDialect dialect = switch (type) {
            case "oracle" -> new OracleDialect();
            case "sqlserver" -> new SqlServerDialect();
            default -> new PostgreSqlDialect();
        };
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseMajorVersion()).thenReturn(type.equals("oracle") ? 19 : 16);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn("id");
        when(rows.getString(2)).thenReturn("Identifier");
        when(rows.getInt(3)).thenReturn(1);
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("name", "id");
        String schema = "owner_%'";
        String table = "Mixed_Table'";
        new TableDetails(connection, dialect, 7).enrichColumns(schema, table, List.of(column));
        assertEquals("Identifier", column.get("comment"));
        assertEquals(true, column.get("identity"));
        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertFalse(sql.getValue().contains(schema));
        assertFalse(sql.getValue().contains(table));
        verify(statement).setString(1, schema);
        verify(statement).setString(2, table);
        verify(statement).setQueryTimeout(7);
        verify(statement).close();
        verify(rows).close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.7.44", "8.0.12", "8.0.13", "8.4.0", "9.7.0"})
    void mysqlIndexExpressionsAreVersionGatedAndExpressionColumnsAreNotInvented(String version) throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseMajorVersion()).thenReturn(Integer.parseInt(version.split("\\.")[0]));
        when(metadata.getDatabaseMinorVersion()).thenReturn(Integer.parseInt(version.split("\\.")[1]));
        when(metadata.getDatabaseProductVersion()).thenReturn(version);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getString(1)).thenReturn("ix_mixed");
        when(rows.getInt(2)).thenReturn(1);
        when(rows.getString(3)).thenReturn("BTREE");
        when(rows.getString(5)).thenReturn("label", "hidden");
        when(rows.getString(6)).thenReturn(null, "lower(`label`)");
        when(rows.getString(7)).thenReturn("ASC", "DESC");
        var indexes = new TableDetails(connection, new MySqlDialect(), 10).indexes("db", null, "t");
        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertEquals(!version.equals("5.7.44") && !version.equals("8.0.12"), sql.getValue().contains(", expression,"));
        var columns = maps(indexes.getFirst().get("columns"));
        assertEquals("label", columns.getFirst().get("name"));
        assertNull(columns.get(1).get("name"));
        assertEquals("lower(`label`)", columns.get(1).get("expression"));
        verify(statement).setString(1, "db");
        verify(statement).setString(2, "t");
    }

    @Test
    void emptyAndUnavailableAutoincrementFlagsRemainUnknown() throws Exception {
        ResultSet column = mock(ResultSet.class);
        when(column.getString("IS_AUTOINCREMENT")).thenReturn("", "NO", "YES", null);
        assertNull(TableDetails.autoIncrement(column));
        assertEquals(false, TableDetails.autoIncrement(column));
        assertEquals(true, TableDetails.autoIncrement(column));
        assertNull(TableDetails.autoIncrement(column));
    }

    @Test
    void mysqlUniqueConstraintsAvoidUnscopedCatalogJoinsAndKeepCompositeColumnOrder() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getString(1)).thenReturn("uq_pair");
        when(rows.getString(2)).thenReturn("z", "a");
        var constraints = new TableDetails(connection, new MySqlDialect(), 10).uniqueConstraints("demo", null, "t");
        assertEquals(List.of(Map.of("name", "uq_pair", "columns", List.of("z", "a"))), constraints);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertFalse(sql.getValue().contains("c.table_catalog"));
        assertFalse(sql.getValue().contains("JOIN"));
        assertTrue(sql.getValue().contains("referenced_table_name IS NULL"));
        assertTrue(sql.getValue().contains("constraint_name <> 'PRIMARY'"));
        verify(statement).setString(1, "demo");
        verify(statement).setString(2, "t");
    }

    @Test
    void catalogFailuresPropagateInsteadOfReportingNoConstraints() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenThrow(new SQLException("permission denied", "42501"));
        assertThrows(SQLException.class, () -> new TableDetails(connection, new OracleDialect(), 10)
                .uniqueConstraints(null, "APP", "EVENTS"));
        verify(statement).close();
    }

    private static DatabaseReadService service(Connection connection) throws Exception {
        var manager = mock(DatabaseRuntimeManager.class);
        var lease = mock(DatabaseRuntimeManager.Lease.class);
        when(manager.acquire()).thenReturn(lease);
        when(lease.connection()).thenReturn(connection);
        when(lease.datasourceId()).thenReturn("ds-metadata");
        when(lease.dialect()).thenReturn(new DatabaseDialect() {
            public String name() { return "H2"; }
            public String urlPrefix() { return "jdbc:h2:"; }
            public String driverClassName() { return "org.h2.Driver"; }
            public String schemaPattern(Connection c) throws SQLException { return c.getSchema(); }
        });
        return new DatabaseReadService(manager, new ReadOnlySqlGuard(), 100, 10, 4000);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) { return (List<Map<String, Object>>) value; }

    private static Map<String, Object> named(List<Map<String, Object>> values, String name) {
        return values.stream().filter(value -> name.equals(value.get("name"))).findFirst().orElseThrow();
    }
}
