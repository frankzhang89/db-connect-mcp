package com.example.dbconnectmcp.database;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Metadata-only queries on the caller's pinned connection. All object names are bound values. */
final class TableDetails {
    private final Connection connection;
    private final DatabaseDialect dialect;
    private final int timeout;

    TableDetails(Connection connection, DatabaseDialect dialect, int timeout) {
        this.connection = connection;
        this.dialect = dialect;
        this.timeout = timeout;
    }

    /** Read native comments when JDBC remarks may be absent or require driver opt-in. */
    Map<String, String> tableComments(String catalog, String schema) throws SQLException {
        String sql;
        String namespace = schema;
        if (dialect instanceof MySqlDialect) {
            sql = "SELECT table_name, table_comment FROM information_schema.tables WHERE table_schema = ?";
            namespace = catalog;
        }
        else if (dialect instanceof OracleDialect) {
            sql = "SELECT table_name, comments FROM all_tab_comments WHERE owner = ?";
        }
        else if (dialect instanceof SqlServerDialect) {
            sql = """
                    SELECT o.name, CAST(p.value AS nvarchar(max))
                    FROM sys.objects o JOIN sys.schemas s ON s.schema_id = o.schema_id
                    LEFT JOIN sys.extended_properties p ON p.class = 1 AND p.major_id = o.object_id
                      AND p.minor_id = 0 AND p.name = N'MS_Description'
                    WHERE s.name = ? AND o.type IN ('U', 'V')
                    """;
        }
        else { return Map.of(); }
        Map<String, String> comments = new LinkedHashMap<>();
        query(sql, java.util.Collections.singletonList(namespace), rows -> comments.put(rows.getString(1), rows.getString(2)));
        return comments;
    }

    void enrichColumns(String schema, String table, List<Map<String, Object>> columns) throws SQLException {
        String sql;
        if (dialect instanceof OracleDialect) {
            // Pre-12c Oracle has no Identity columns. Do not reference the newer view there.
            boolean identities = connection.getMetaData().getDatabaseMajorVersion() >= 12;
            sql = "SELECT c.column_name, c.comments, "
                    + (identities ? "CASE WHEN i.column_name IS NULL THEN 0 ELSE 1 END" : "0")
                    + " FROM all_col_comments c "
                    + (identities ? "LEFT JOIN all_tab_identity_cols i ON i.owner = c.owner AND i.table_name = c.table_name AND i.column_name = c.column_name " : "")
                    + "WHERE c.owner = ? AND c.table_name = ?";
        }
        else if (dialect instanceof SqlServerDialect) {
            sql = """
                    SELECT c.name, CAST(p.value AS nvarchar(max)), c.is_identity
                    FROM sys.columns c JOIN sys.objects o ON o.object_id = c.object_id
                    JOIN sys.schemas s ON s.schema_id = o.schema_id
                    LEFT JOIN sys.extended_properties p ON p.class = 1 AND p.major_id = c.object_id
                      AND p.minor_id = c.column_id AND p.name = N'MS_Description'
                    WHERE s.name = ? AND o.name = ?
                    """;
        }
        else if (dialect instanceof PostgreSqlDialect) {
            // Identity was introduced in PG 10; serial remains autoIncrement, not identity.
            String identity = connection.getMetaData().getDatabaseMajorVersion() >= 10
                    ? "CASE WHEN a.attidentity <> '' THEN 1 ELSE 0 END" : "0";
            sql = "SELECT a.attname, pg_catalog.col_description(t.oid, a.attnum), " + identity
                    + " FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class t ON t.oid = a.attrelid"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace"
                    + " WHERE n.nspname = ? AND t.relname = ? AND a.attnum > 0 AND NOT a.attisdropped";
        }
        else { return; }
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        columns.forEach(column -> byName.put((String) column.get("name"), column));
        query(sql, List.of(schema, table), rows -> {
            Map<String, Object> column = byName.get(rows.getString(1));
            if (column != null) {
                column.put("comment", rows.getString(2));
                column.put("identity", rows.getInt(3) != 0);
            }
        });
    }

    static Boolean autoIncrement(ResultSet column) throws SQLException {
        try {
            String flag = column.getString("IS_AUTOINCREMENT");
            return "YES".equalsIgnoreCase(flag) ? Boolean.TRUE : "NO".equalsIgnoreCase(flag) ? Boolean.FALSE : null;
        }
        catch (SQLFeatureNotSupportedException unsupported) { return null; }
    }

    List<Map<String, Object>> indexes(String catalog, String schema, String table) throws SQLException {
        // StarRocks' information_schema.statistics is a placeholder, not an index inventory.
        if (dialect instanceof StarRocksDialect) { return null; }
        String sql;
        List<String> args = List.of(schema == null ? catalog : schema, table);
        if (dialect instanceof MySqlDialect) {
            DatabaseMetaData metadata = connection.getMetaData();
            // EXPRESSION was added in 8.0.13, not in the first 8.0 release.
            boolean expressions = metadata.getDatabaseMajorVersion() > 8
                    || (metadata.getDatabaseMajorVersion() == 8 && (metadata.getDatabaseMinorVersion() > 0
                    || mysqlPatch(metadata.getDatabaseProductVersion()) >= 13));
            sql = "SELECT index_name, CASE WHEN non_unique = 0 THEN 1 ELSE 0 END, index_type, seq_in_index, column_name, "
                    + (expressions ? "expression" : "NULL")
                    + ", CASE collation WHEN 'A' THEN 'ASC' WHEN 'D' THEN 'DESC' ELSE NULL END"
                    + " FROM information_schema.statistics WHERE table_schema = ? AND table_name = ? ORDER BY index_name, seq_in_index";
            args = List.of(catalog, table);
        }
        else if (dialect instanceof OracleDialect) {
            sql = """
                    SELECT i.index_name, CASE WHEN i.uniqueness = 'UNIQUE' THEN 1 ELSE 0 END,
                           i.index_type, c.column_position, c.column_name, e.column_expression, c.descend
                    FROM all_indexes i JOIN all_ind_columns c ON c.index_owner = i.owner AND c.index_name = i.index_name
                    LEFT JOIN all_ind_expressions e ON e.index_owner = c.index_owner AND e.index_name = c.index_name
                      AND e.column_position = c.column_position
                    WHERE i.table_owner = ? AND i.table_name = ? ORDER BY i.index_name, c.column_position
                    """;
        }
        else if (dialect instanceof PostgreSqlDialect) {
            String keyCount = connection.getMetaData().getDatabaseMajorVersion() >= 11 ? "i.indnkeyatts" : "i.indnatts";
            sql = """
                    SELECT ix.relname, CASE WHEN i.indisunique THEN 1 ELSE 0 END, am.amname, k.pos,
                           a.attname, CASE WHEN i.indkey[k.pos - 1] = 0 THEN pg_catalog.pg_get_indexdef(i.indexrelid, k.pos, true) END,
                           CASE WHEN am.amname = 'btree' THEN CASE WHEN (i.indoption[k.pos - 1] & 1) = 1 THEN 'DESC' ELSE 'ASC' END END
                    FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class t ON t.oid = i.indrelid
                    JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
                    JOIN pg_catalog.pg_class ix ON ix.oid = i.indexrelid
                    JOIN pg_catalog.pg_am am ON am.oid = ix.relam
                    CROSS JOIN LATERAL generate_series(1, %s) k(pos)
                    LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid = t.oid AND a.attnum = i.indkey[k.pos - 1]
                    WHERE n.nspname = ? AND t.relname = ? ORDER BY ix.relname, k.pos
                    """.formatted(keyCount);
        }
        else if (dialect instanceof SqlServerDialect) {
            sql = """
                    SELECT i.name, CAST(i.is_unique AS int), i.type_desc, ic.key_ordinal, c.name, NULL,
                           CASE WHEN i.type IN (1, 2) THEN CASE WHEN ic.is_descending_key = 1 THEN 'DESC' ELSE 'ASC' END END
                    FROM sys.indexes i JOIN sys.objects t ON t.object_id = i.object_id
                    JOIN sys.schemas s ON s.schema_id = t.schema_id
                    LEFT JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
                      AND (ic.key_ordinal > 0 OR i.type IN (3, 4, 5, 6))
                    LEFT JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                    WHERE s.name = ? AND t.name = ? AND i.index_id > 0 AND i.is_hypothetical = 0
                    ORDER BY i.name, ic.key_ordinal, ic.index_column_id
                    """;
        }
        else { return jdbcIndexes(catalog, schema, table); }
        Map<String, Map<String, Object>> indexes = new LinkedHashMap<>();
        query(sql, args, row -> {
            String name = row.getString(1);
            Map<String, Object> index = indexes.get(name);
            if (index == null) {
                index = index(name, row.getInt(2) != 0, row.getString(3));
                indexes.put(name, index);
            }
            String column = row.getString(5);
            String expression = row.getString(6);
            String direction = row.getString(7);
            if (column != null || expression != null) {
                indexColumns(index).add(indexColumn(expression == null ? column : null, expression, direction));
            }
        });
        return new ArrayList<>(indexes.values());
    }

    private List<Map<String, Object>> jdbcIndexes(String catalog, String schema, String table) throws SQLException {
        Map<String, Map<String, Object>> indexes = new LinkedHashMap<>();
        Map<String, TreeMap<Integer, Map<String, Object>>> ordered = new LinkedHashMap<>();
        try (ResultSet rows = connection.getMetaData().getIndexInfo(catalog, schema, table, false, true)) {
            while (rows.next()) {
                if (rows.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) { continue; }
                String name = rows.getString("INDEX_NAME");
                if (!indexes.containsKey(name)) {
                    indexes.put(name, index(name, !rows.getBoolean("NON_UNIQUE"), switch (rows.getShort("TYPE")) {
                        case DatabaseMetaData.tableIndexClustered -> "CLUSTERED";
                        case DatabaseMetaData.tableIndexHashed -> "HASHED";
                        default -> "OTHER";
                    }));
                    ordered.put(name, new TreeMap<>());
                }
                String direction = rows.getString("ASC_OR_DESC");
                ordered.get(name).put(rows.getInt("ORDINAL_POSITION"), indexColumn(rows.getString("COLUMN_NAME"), null,
                        "A".equals(direction) ? "ASC" : "D".equals(direction) ? "DESC" : null));
            }
        }
        catch (SQLFeatureNotSupportedException unsupported) { return null; }
        indexes.forEach((name, index) -> indexColumns(index).addAll(ordered.get(name).values()));
        return new ArrayList<>(indexes.values());
    }

    List<Map<String, Object>> uniqueConstraints(String catalog, String schema, String table) throws SQLException {
        if (dialect instanceof StarRocksDialect) { return null; }
        String sql;
        List<String> args;
        if (dialect instanceof OracleDialect) {
            sql = """
                    SELECT c.constraint_name, k.column_name FROM all_constraints c JOIN all_cons_columns k
                      ON k.owner = c.owner AND k.constraint_name = c.constraint_name AND k.table_name = c.table_name
                    WHERE c.owner = ? AND c.table_name = ? AND c.constraint_type = 'U'
                    ORDER BY c.constraint_name, k.position
                    """;
            args = List.of(schema, table);
        }
        else if (dialect instanceof MySqlDialect) {
            // MySQL KEY_COLUMN_USAGE contains PRIMARY, UNIQUE and FOREIGN keys only.
            // Query it directly: joining TABLE_CONSTRAINTS can open unrelated tables on older servers.
            sql = """
                    SELECT constraint_name, column_name FROM information_schema.key_column_usage
                    WHERE table_schema = ? AND table_name = ? AND constraint_name <> 'PRIMARY'
                      AND referenced_table_name IS NULL ORDER BY constraint_name, ordinal_position
                    """;
            args = List.of(catalog, table);
        }
        else if (dialect instanceof PostgreSqlDialect) {
            sql = """
                    SELECT c.conname, a.attname FROM pg_catalog.pg_constraint c
                    JOIN pg_catalog.pg_class t ON t.oid = c.conrelid
                    JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
                    CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY k(attnum, pos)
                    JOIN pg_catalog.pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
                    WHERE n.nspname = ? AND t.relname = ? AND c.contype = 'u' ORDER BY c.conname, k.pos
                    """;
            args = List.of(schema, table);
        }
        else {
            // INFORMATION_SCHEMA distinguishes unique constraints from standalone unique indexes.
            sql = """
                    SELECT c.constraint_name, k.column_name
                    FROM information_schema.table_constraints c JOIN information_schema.key_column_usage k
                      ON k.constraint_catalog = c.constraint_catalog AND k.constraint_schema = c.constraint_schema
                      AND k.constraint_name = c.constraint_name AND k.table_name = c.table_name
                      AND k.table_schema = c.table_schema
                    WHERE c.table_catalog = ? AND c.table_schema = ? AND c.table_name = ? AND c.constraint_type = 'UNIQUE'
                    ORDER BY c.constraint_name, k.ordinal_position
                    """;
            args = List.of(catalog, schema, table);
        }
        Map<String, List<String>> groups = new LinkedHashMap<>();
        query(sql, args, row -> groups.computeIfAbsent(row.getString(1), ignored -> new ArrayList<>()).add(row.getString(2)));
        List<Map<String, Object>> constraints = new ArrayList<>();
        groups.forEach((name, columns) -> constraints.add(Map.of("name", name, "columns", columns)));
        return constraints;
    }

    List<Map<String, Object>> foreignKeys(String catalog, String schema, String table) throws SQLException {
        if (dialect instanceof StarRocksDialect) { return null; }
        Map<String, List<ForeignKeyColumn>> groups = new LinkedHashMap<>();
        try (ResultSet rows = connection.getMetaData().getImportedKeys(catalog, schema, table)) {
            int unnamed = 0;
            while (rows.next()) {
                String name = rows.getString("FK_NAME");
                int position = rows.getInt("KEY_SEQ");
                if (name == null && position == 1) { unnamed++; }
                String key = name == null ? "unnamed:" + unnamed : "named:" + name;
                Map<String, Object> header = new LinkedHashMap<>();
                header.put("name", name);
                header.put("referencedCatalog", rows.getString("PKTABLE_CAT"));
                header.put("referencedSchema", rows.getString("PKTABLE_SCHEM"));
                header.put("referencedTable", rows.getString("PKTABLE_NAME"));
                header.put("updateRule", rule(rows.getInt("UPDATE_RULE")));
                header.put("deleteRule", rule(rows.getInt("DELETE_RULE")));
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new ForeignKeyColumn(position,
                        rows.getString("FKCOLUMN_NAME"), rows.getString("PKCOLUMN_NAME"), header));
            }
        }
        catch (SQLFeatureNotSupportedException unsupported) { return null; }
        List<Map<String, Object>> keys = new ArrayList<>();
        for (List<ForeignKeyColumn> group : groups.values()) {
            group.sort(Comparator.comparingInt(ForeignKeyColumn::position));
            Map<String, Object> key = group.getFirst().header();
            key.put("columns", group.stream().map(ForeignKeyColumn::column).toList());
            key.put("referencedColumns", group.stream().map(ForeignKeyColumn::referencedColumn).toList());
            keys.add(key);
        }
        return keys;
    }

    private record ForeignKeyColumn(int position, String column, String referencedColumn, Map<String, Object> header) { }

    private static String rule(int value) {
        return switch (value) {
            case DatabaseMetaData.importedKeyCascade -> "CASCADE";
            case DatabaseMetaData.importedKeyRestrict -> "RESTRICT";
            case DatabaseMetaData.importedKeySetNull -> "SET_NULL";
            case DatabaseMetaData.importedKeyNoAction -> "NO_ACTION";
            case DatabaseMetaData.importedKeySetDefault -> "SET_DEFAULT";
            default -> null;
        };
    }

    private static int mysqlPatch(String version) {
        if (version == null) { return 0; }
        var match = java.util.regex.Pattern.compile("^8\\.0\\.(\\d+)").matcher(version);
        return match.find() ? Integer.parseInt(match.group(1)) : 0;
    }

    private static Map<String, Object> index(String name, boolean unique, String type) {
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("name", name);
        index.put("unique", unique);
        index.put("type", type);
        index.put("columns", new ArrayList<Map<String, Object>>());
        return index;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> indexColumns(Map<String, Object> index) {
        return (List<Map<String, Object>>) index.get("columns");
    }

    private static Map<String, Object> indexColumn(String name, String expression, String direction) {
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("name", name);
        column.put("expression", expression);
        column.put("sortDirection", direction);
        return column;
    }

    private void query(String sql, List<String> args, RowConsumer consumer) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeout);
            for (int i = 0; i < args.size(); i++) { statement.setString(i + 1, args.get(i)); }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) { consumer.accept(rows); }
            }
        }
    }

    @FunctionalInterface
    private interface RowConsumer { void accept(ResultSet row) throws SQLException; }
}
