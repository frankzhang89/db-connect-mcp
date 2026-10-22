package com.example.dbconnectmcp.database;

import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Read tools lease the current global JDBC target for the whole operation.
 * This component only adapts MCP parameters; the service owns validation, JDBC
 * resource handling, and result limits.
 */
@Component
public class DatabaseTools {

    private final DatabaseReadService service;

    public DatabaseTools(DatabaseReadService service) {
        this.service = service;
    }

    @McpTool(name = "db_list_tables", description = "List tables and views in the configured JDBC database and schema; returns at most 500 entries with comments and catalog/schema information when available.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Map<String, Object> listTables() {
        return service.listTables();
    }

    @McpTool(name = "db_describe_table", description = "Inspect table/column comments, column types, sizes, nullability, defaults, auto-increment/identity flags, primary key, indexes, foreign keys, and unique constraints. Unsupported or unknown metadata is null; Oracle sequence associations are not inspected.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Map<String, Object> describeTable(
            @McpToolParam(description = "Table name; quote mixed-case identifiers with double quotes when needed", required = true) String tableName,
            @McpToolParam(description = "Optional schema override; defaults to the configured or current schema", required = false) String schemaName) {
        return service.describeTable(tableName, schemaName);
    }

    @McpTool(name = "db_query", description = "Run one read-only SELECT query (WITH ... SELECT allowed); bind values with ? placeholders and the parameters argument. Returns at most 100 rows within a 10 second timeout.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Map<String, Object> query(
            @McpToolParam(description = "Read-only SELECT SQL", required = true) String sql,
            @McpToolParam(description = "Scalar parameters in ? order, optional", required = false) List<Object> parameters,
            @McpToolParam(description = "Maximum number of rows to return, optional", required = false) Integer maxRows) {
        return service.query(sql, parameters, maxRows);
    }
}
