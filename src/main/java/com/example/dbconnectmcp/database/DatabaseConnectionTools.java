package com.example.dbconnectmcp.database;

import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP entry point for replacing the JDBC target shared by every client of this server.
 * Switches are memory-only; Redis maintains its own independent database selection.
 */
@Component
public class DatabaseConnectionTools {
    private final DatabaseRuntimeManager manager;

    public DatabaseConnectionTools(DatabaseRuntimeManager manager) { this.manager = manager; }

    @McpTool(name = "db_switch_datasource", description = "Validate and switch the JDBC database for ALL clients without restarting. Supply JDBC URL, username, password (may be empty), and optional metadata schema. Failed validation leaves the current database unchanged. Existing operations finish on their original database. Changes are memory-only; restart restores YAML settings. Redis is independent.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true))
    public Map<String, Object> switchDatasource(
            @McpToolParam(description = "JDBC URL for MySQL, Oracle, SQL Server, PostgreSQL, or native StarRocks", required = true) String jdbcUrl,
            @McpToolParam(description = "Database account", required = true) String username,
            @McpToolParam(description = "Database password; an empty string is allowed", required = true) String password,
            @McpToolParam(description = "Optional metadata schema; omission uses the new connection default", required = false) String schema) {
        // Capture a complete new configuration; do not inherit the old target's schema
        // or credentials when optional parameters are omitted.
        return manager.switchTo(new DatabaseConnectionSettings(jdbcUrl, username, password, schema));
    }
}
