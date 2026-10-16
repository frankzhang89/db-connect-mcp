package com.example.dbconnectmcp.database;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import static com.example.dbconnectmcp.database.DatabaseMetadata.*;

/**
 * Read-only database access. Every operation borrows a pooled connection,
 * applies the dialect's read policy. JDBC read-only hints and rollback are
 * additional safeguards, not a replacement for database permissions.
 */
@Service
public class DatabaseReadService {

    // Hard cap for db_list_tables responses.
    private static final int MAX_TABLES = 500;

    private final DatabaseRuntimeManager manager;
    private final ReadOnlySqlGuard sqlGuard;
    private final int maxRows;
    private final int timeoutSeconds;
    private final int maxCellChars;

    public DatabaseReadService(DatabaseRuntimeManager manager, ReadOnlySqlGuard sqlGuard,
            @Value("${dbmcp.query.max-rows:100}") int maxRows,
            @Value("${dbmcp.query.timeout-seconds:10}") int timeoutSeconds,
            @Value("${dbmcp.query.max-cell-chars:4000}") int maxCellChars) {
        this.manager = manager;
        this.sqlGuard = sqlGuard;
        this.maxRows = Math.max(1, maxRows);
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.maxCellChars = Math.max(1, maxCellChars);
    }

    public Map<String, Object> listTables() {
        List<Map<String, String>> tables = new ArrayList<>();
        boolean truncated = false;
        String datasourceId;
        String databaseType;
        try (var lease = manager.acquire(); Connection connection = lease.connection()) {
            DatabaseDialect dialect = lease.dialect();
            datasourceId = lease.datasourceId();
            databaseType = dialect.name();
            dialect.prepareRead(connection);
            // JDBC metadata lists tables and views without touching user data.
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet result = metadata.getTables(dialect.catalog(connection),
                    escapePattern(schema(connection, dialect, lease.schema(), null), metadata), "%", new String[] {"TABLE", "VIEW"})) {
                while (result.next()) {
                    if (tables.size() >= MAX_TABLES) {
                        truncated = true;
                        break;
                    }
                    Map<String, String> table = new LinkedHashMap<>();
                    table.put("name", result.getString("TABLE_NAME"));
                    table.put("type", result.getString("TABLE_TYPE"));
                    if (result.getString("TABLE_CAT") != null) {
                        table.put("catalog", result.getString("TABLE_CAT"));
                    }
                    if (result.getString("TABLE_SCHEM") != null) {
                        table.put("schema", result.getString("TABLE_SCHEM"));
                    }
                    tables.add(table);
                }
            }
        }
        catch (SQLException ex) {
            throw databaseFailure(ex);
        }
        return Map.of("datasourceId", datasourceId, "databaseType", databaseType, "tables", tables, "truncated", truncated);
    }

    public Map<String, Object> describeTable(String tableName) {
        return describeTable(tableName, null);
    }

    public Map<String, Object> describeTable(String tableName, String schemaName) {
        validateIdentifier(tableName);
        List<Map<String, Object>> columns = new ArrayList<>();
        List<String> primaryKeys = new ArrayList<>();
        Map<String, Object> description = new LinkedHashMap<>();
        try (var lease = manager.acquire(); Connection connection = lease.connection()) {
            DatabaseDialect dialect = lease.dialect();
            description.put("datasourceId", lease.datasourceId());
            dialect.prepareRead(connection);
            DatabaseMetaData metadata = connection.getMetaData();
            String catalog = dialect.catalog(connection);
            String schema = schema(connection, dialect, lease.schema(), schemaName);
            String exactTable = normalizeIdentifier(tableName, metadata);
            String actualTable = exactTable;
            boolean caseInsensitive = metadata.storesMixedCaseIdentifiers() && !metadata.supportsMixedCaseIdentifiers();
            // Escape JDBC pattern wildcards in literal table/schema names.
            try (ResultSet result = metadata.getColumns(catalog, escapePattern(schema, metadata),
                    escapePattern(exactTable, metadata), "%")) {
                while (result.next()) {
                    String foundTable = result.getString("TABLE_NAME");
                    if (!exactTable.equals(foundTable) && !(caseInsensitive && exactTable.equalsIgnoreCase(foundTable))) {
                        continue;
                    }
                    actualTable = foundTable;
                    Map<String, Object> column = new LinkedHashMap<>();
                    column.put("name", result.getString("COLUMN_NAME"));
                    column.put("type", result.getString("TYPE_NAME"));
                    column.put("size", result.getInt("COLUMN_SIZE"));
                    column.put("nullable", result.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
                    columns.add(column);
                }
            }
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("The table was not found or its structure cannot be read.");
            }
            try (ResultSet result = metadata.getPrimaryKeys(catalog, schema, actualTable)) {
                while (result.next()) {
                    primaryKeys.add(result.getString("COLUMN_NAME"));
                }
            }
            description.put("table", actualTable);
            if (catalog != null) {
                description.put("catalog", catalog);
            }
            if (schema != null) {
                description.put("schema", schema);
            }
        }
        catch (SQLException ex) {
            throw databaseFailure(ex);
        }
        description.put("columns", columns);
        description.put("primaryKeys", primaryKeys);
        return description;
    }

    public Map<String, Object> query(String sql, List<Object> parameters, Integer requestedRows) {
        // Whitelist validation happens before any connection is opened.
        sqlGuard.validate(sql);
        // Clamp the requested row limit to the configured maximum.
        int rowLimit = requestedRows == null ? maxRows : Math.min(Math.max(1, requestedRows), maxRows);
        List<Object> args = parameters == null ? List.of() : parameters;
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> columnNames = new ArrayList<>();
        boolean truncated = false;
        String datasourceId;
        try (var lease = manager.acquire(); Connection connection = lease.connection()) {
            DatabaseDialect dialect = lease.dialect();
            datasourceId = lease.datasourceId();
            dialect.beginRead(connection);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(timeoutSeconds);
                // Fetch one extra row to detect truncation without a second count query.
                statement.setMaxRows(rowLimit + 1);
                for (int i = 0; i < args.size(); i++) {
                    Object arg = args.get(i);
                    if (arg != null && !(arg instanceof String || arg instanceof Number || arg instanceof Boolean)) {
                        throw new IllegalArgumentException("SQL parameters may only be strings, numbers, booleans, or null.");
                    }
                    statement.setObject(i + 1, arg);
                }
                try (ResultSet result = statement.executeQuery()) {
                    ResultSetMetaData metadata = result.getMetaData();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        columnNames.add(metadata.getColumnLabel(i));
                    }
                    while (result.next()) {
                        if (rows.size() >= rowLimit) {
                            truncated = true;
                            break;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) {
                            row.put(columnNames.get(i - 1), safeValue(result.getObject(i)));
                        }
                        rows.add(row);
                    }
                }
            }
            finally {
                // End read transactions without committing any changes.
                if (dialect.usesReadTransaction()) {
                    connection.rollback();
                }
            }
        }
        catch (SQLException ex) {
            throw databaseFailure(ex);
        }
        return Map.of("datasourceId", datasourceId, "columns", columnNames, "rows", rows, "rowCount", rows.size(),
                "truncated", truncated, "maxRows", rowLimit);
    }

    /**
     * Converts a JDBC value into an MCP-safe payload: binary data becomes
     * bounded base64, and long text is truncated at maxCellChars.
     */
    private Object safeValue(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            int length = Math.min(bytes.length, maxCellChars);
            return Map.of("base64", Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(bytes, length)),
                    "truncated", bytes.length > length);
        }
        if (value instanceof Blob blob) {
            int length = (int) Math.min(blob.length(), maxCellChars);
            return Map.of("base64", Base64.getEncoder().encodeToString(blob.getBytes(1, length)),
                    "truncated", blob.length() > length);
        }
        if (value instanceof Clob clob) {
            int length = (int) Math.min(clob.length(), maxCellChars);
            return truncate(clob.getSubString(1, length), clob.length() > length);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        String text = value.toString();
        return truncate(text, text.length() > maxCellChars);
    }

    private Object truncate(String text, boolean alreadyTruncated) {
        boolean truncated = alreadyTruncated || text.length() > maxCellChars;
        String output = text.substring(0, Math.min(text.length(), maxCellChars));
        return truncated ? Map.of("text", output, "truncated", true) : output;
    }

    private IllegalStateException databaseFailure(SQLException ex) {
        String sqlState = ex.getSQLState();
        return new IllegalStateException("Database read failed" + (sqlState == null ? "." : " (SQLState: " + sqlState + ")."));
    }
}
