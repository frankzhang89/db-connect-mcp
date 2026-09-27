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
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DatabaseReadService {

    private final DataSource dataSource;
    private final DatabaseDialect dialect;
    private final ReadOnlySqlGuard sqlGuard;
    private final int maxRows;
    private final int timeoutSeconds;
    private final int maxCellChars;

    public DatabaseReadService(DataSource dataSource, DatabaseProperties properties,
            DatabaseDialectRegistry registry, ReadOnlySqlGuard sqlGuard,
            @Value("${dbmcp.query.max-rows:100}") int maxRows,
            @Value("${dbmcp.query.timeout-seconds:10}") int timeoutSeconds,
            @Value("${dbmcp.query.max-cell-chars:4000}") int maxCellChars) {
        this.dataSource = dataSource;
        this.dialect = registry.resolve(properties.url());
        this.sqlGuard = sqlGuard;
        this.maxRows = Math.max(1, maxRows);
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.maxCellChars = Math.max(1, maxCellChars);
    }

    public Map<String, Object> listTables() {
        List<Map<String, String>> tables = new ArrayList<>();
        boolean truncated = false;
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet result = metadata.getTables(dialect.catalog(connection),
                    dialect.schemaPattern(connection), "%", new String[] {"TABLE", "VIEW"})) {
                while (result.next()) {
                    if (tables.size() >= 500) {
                        truncated = true;
                        break;
                    }
                    tables.add(Map.of(
                            "name", result.getString("TABLE_NAME"),
                            "type", result.getString("TABLE_TYPE")));
                }
            }
        }
        catch (SQLException ex) {
            throw databaseFailure(ex);
        }
        return Map.of("databaseType", dialect.name(), "tables", tables, "truncated", truncated);
    }

    public Map<String, Object> describeTable(String tableName) {
        if (tableName == null || tableName.isBlank() || tableName.length() > 128) {
            throw new IllegalArgumentException("表名不能为空且长度不能超过 128 个字符。");
        }
        List<Map<String, Object>> columns = new ArrayList<>();
        List<String> primaryKeys = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            DatabaseMetaData metadata = connection.getMetaData();
            String catalog = dialect.catalog(connection);
            String schema = dialect.schemaPattern(connection);
            try (ResultSet result = metadata.getColumns(catalog, schema, tableName, "%")) {
                while (result.next()) {
                    if (!tableName.equalsIgnoreCase(result.getString("TABLE_NAME"))) {
                        continue;
                    }
                    Map<String, Object> column = new LinkedHashMap<>();
                    column.put("name", result.getString("COLUMN_NAME"));
                    column.put("type", result.getString("TYPE_NAME"));
                    column.put("size", result.getInt("COLUMN_SIZE"));
                    column.put("nullable", result.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
                    columns.add(column);
                }
            }
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("未找到指定表或无权读取其结构。");
            }
            try (ResultSet result = metadata.getPrimaryKeys(catalog, schema, tableName)) {
                while (result.next()) {
                    primaryKeys.add(result.getString("COLUMN_NAME"));
                }
            }
        }
        catch (SQLException ex) {
            throw databaseFailure(ex);
        }
        return Map.of("table", tableName, "columns", columns, "primaryKeys", primaryKeys);
    }

    public Map<String, Object> query(String sql, List<Object> parameters, Integer requestedRows) {
        sqlGuard.validate(sql);
        int rowLimit = requestedRows == null ? maxRows : Math.min(Math.max(1, requestedRows), maxRows);
        List<Object> args = parameters == null ? List.of() : parameters;
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> columnNames = new ArrayList<>();
        boolean truncated = false;
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(timeoutSeconds);
                statement.setMaxRows(rowLimit + 1);
                for (int i = 0; i < args.size(); i++) {
                    Object arg = args.get(i);
                    if (arg != null && !(arg instanceof String || arg instanceof Number || arg instanceof Boolean)) {
                        throw new IllegalArgumentException("SQL 参数只能是字符串、数字、布尔值或 null。");
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
                connection.rollback();
            }
        }
        catch (SQLException ex) {
            throw databaseFailure(ex);
        }
        return Map.of("columns", columnNames, "rows", rows, "rowCount", rows.size(),
                "truncated", truncated, "maxRows", rowLimit);
    }

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
        return new IllegalStateException("数据库读取失败" + (sqlState == null ? "。" : "（SQLState: " + sqlState + "）。"));
    }
}
