package com.example.dbconnectmcp.database;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded JDBC result conversion for SELECT, RETURNING, and OUTPUT row sets. */
final class TransactionResultMapper {
    private final int maxRows;
    private final int maxCellChars;

    TransactionResultMapper(int maxRows, int maxCellChars) {
        this.maxRows = Math.max(1, maxRows);
        this.maxCellChars = Math.max(1, maxCellChars);
    }

    Map<String, Object> read(ResultSet result) throws SQLException {
        var metadata = result.getMetaData();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) { columns.add(metadata.getColumnLabel(i)); }
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (result.next()) {
            if (rows.size() >= maxRows) { truncated = true; break; }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columns.size(); i++) { row.put(columns.get(i - 1), value(result.getObject(i))); }
            rows.add(row);
        }
        return Map.of("type", "QUERY", "columns", columns, "rows", rows,
                "rowCount", rows.size(), "truncated", truncated, "maxRows", maxRows);
    }

    private Object value(Object value) throws SQLException {
        if (value == null || value instanceof Number || value instanceof Boolean) { return value; }
        if (value instanceof byte[] bytes) {
            return Map.of("base64", Base64.getEncoder().encodeToString(Arrays.copyOf(bytes, Math.min(bytes.length, maxCellChars))),
                    "truncated", bytes.length > maxCellChars);
        }
        if (value instanceof Blob blob) {
            try {
                return Map.of("base64", Base64.getEncoder().encodeToString(blob.getBytes(1, (int) Math.min(blob.length(), maxCellChars))),
                        "truncated", blob.length() > maxCellChars);
            }
            finally { blob.free(); }
        }
        if (value instanceof Clob clob) {
            try { return text(clob.getSubString(1, (int) Math.min(clob.length(), maxCellChars)), clob.length() > maxCellChars); }
            finally { clob.free(); }
        }
        return text(value.toString(), false);
    }

    private Object text(String text, boolean truncated) {
        return truncated || text.length() > maxCellChars
                ? Map.of("text", text.substring(0, Math.min(text.length(), maxCellChars)), "truncated", true) : text;
    }
}
