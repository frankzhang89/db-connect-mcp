package com.example.dbconnectmcp.database;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

final class DatabaseMetadata {
    private DatabaseMetadata() { }

    static String schema(Connection connection, DatabaseDialect dialect, String configured, String requested) throws SQLException {
        String selected = requested == null || requested.isBlank() ? configured : requested;
        if (selected != null && !selected.isBlank()) {
            return normalizeIdentifier(selected, connection.getMetaData());
        }
        String current = dialect.schemaPattern(connection);
        if ((current == null || current.isBlank()) && !(dialect instanceof MySqlDialect)) {
            throw new IllegalArgumentException("No current schema; configure dbmcp.database.schema for metadata discovery.");
        }
        return current;
    }

    static void validateIdentifier(String name) {
        if (name == null || name.isBlank() || name.length() > 1024) {
            throw new IllegalArgumentException("The identifier must not be empty and must not exceed 1024 characters.");
        }
    }

    static String normalizeIdentifier(String name, DatabaseMetaData metadata) throws SQLException {
        validateIdentifier(name);
        if (name.length() > 2 && name.startsWith("\"") && name.endsWith("\"")) {
            return name.substring(1, name.length() - 1).replace("\"\"", "\"");
        }
        if (name.length() > 2 && name.startsWith("`") && name.endsWith("`")) {
            return name.substring(1, name.length() - 1).replace("``", "`");
        }
        if (name.length() > 2 && name.startsWith("[") && name.endsWith("]")) {
            return name.substring(1, name.length() - 1).replace("]]", "]");
        }
        if (metadata.storesUpperCaseIdentifiers()) {
            return name.toUpperCase(Locale.ROOT);
        }
        return metadata.storesLowerCaseIdentifiers() ? name.toLowerCase(Locale.ROOT) : name;
    }

    static String escapePattern(String name, DatabaseMetaData metadata) throws SQLException {
        if (name == null) { return null; }
        String escape = metadata.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) { return name; }
        return name.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }
}
