package com.example.dbconnectmcp.database;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

/** Shared namespace and identifier handling for JDBC metadata discovery. */
final class DatabaseMetadata {
    private DatabaseMetadata() { }

    /** Selects per-call override, then target configuration, then connection default. */
    static String schema(Connection connection, DatabaseDialect dialect, String configured, String requested) throws SQLException {
        String selected = requested == null || requested.isBlank() ? configured : requested;
        if (selected != null && !selected.isBlank()) {
            return normalizeIdentifier(selected, connection.getMetaData());
        }
        // This selects a metadata filter only; it never changes the SQL session schema.
        String current = dialect.schemaPattern(connection);
        if ((current == null || current.isBlank()) && !(dialect instanceof MySqlDialect)) {
            throw new IllegalArgumentException("No current schema; configure dbmcp.database.schema for metadata discovery.");
        }
        return current;
    }

    /** Checks presence and size; quoted names are interpreted during normalization. */
    static void validateIdentifier(String name) {
        if (name == null || name.isBlank() || name.length() > 1024) {
            throw new IllegalArgumentException("The identifier must not be empty and must not exceed 1024 characters.");
        }
    }

    /** Preserves quoted case and applies driver-reported folding to unquoted names. */
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

    /** Prevents literal underscores and percent signs from becoming metadata wildcards. */
    static String escapePattern(String name, DatabaseMetaData metadata) throws SQLException {
        if (name == null) { return null; }
        String escape = metadata.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) { return name; }
        return name.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }
}
