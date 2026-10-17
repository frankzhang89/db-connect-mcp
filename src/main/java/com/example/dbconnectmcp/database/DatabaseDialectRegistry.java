package com.example.dbconnectmcp.database;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@link DatabaseDialect} for a JDBC URL by matching registered
 * URL prefixes. Connection strings are never included in error messages.
 * Resolution runs for every candidate, allowing switches between database types
 * without mutating the dialect retained by existing leases.
 */
@Component
public class DatabaseDialectRegistry {

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
        throw new IllegalArgumentException("Unsupported JDBC URL prefix.");
    }
}
