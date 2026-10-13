package com.example.dbconnectmcp.database;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@link DatabaseDialect} for a JDBC URL by matching registered
 * URL prefixes. Prefixes of planned but unimplemented databases fail with an
 * explicit error instead of a generic "unsupported prefix" message.
 */
@Component
public class DatabaseDialectRegistry {

    // Recognized but not yet implemented; kept to fail with a clear message.
    private static final Map<String, String> PLANNED_TYPES = Map.of(
            "jdbc:oracle:", "Oracle",
            "jdbc:sqlserver:", "SQL Server",
            "jdbc:postgresql:", "PostgreSQL",
            "jdbc:starrocks:", "StarRocks");

    private final List<DatabaseDialect> dialects;

    public DatabaseDialectRegistry(List<DatabaseDialect> dialects) {
        this.dialects = List.copyOf(dialects);
    }

    public DatabaseDialect resolve(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Missing dbmcp.database.url; configure the JDBC address in your private YAML.");
        }
        // The first registered dialect whose URL prefix matches wins.
        for (DatabaseDialect dialect : dialects) {
            if (url.startsWith(dialect.urlPrefix())) {
                return dialect;
            }
        }
        for (var planned : PLANNED_TYPES.entrySet()) {
            if (url.startsWith(planned.getKey())) {
                throw new IllegalArgumentException(planned.getValue() + " is recognized but has no adapter implemented yet.");
            }
        }
        throw new IllegalArgumentException("Unsupported JDBC URL prefix.");
    }
}
