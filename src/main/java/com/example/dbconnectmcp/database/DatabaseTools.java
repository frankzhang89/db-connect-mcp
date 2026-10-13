package com.example.dbconnectmcp.database;

import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Read-only MySQL tools exposed over MCP. All of them go through
 * {@link DatabaseReadService}, which forces read-only sessions.
 */
@Component
public class DatabaseTools {

    private final DatabaseReadService service;

    public DatabaseTools(DatabaseReadService service) {
        this.service = service;
    }

    @McpTool(name = "db_list_tables", description = "List tables and views in the configured MySQL database; returns at most 500 entries.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Map<String, Object> listTables() {
        return service.listTables();
    }

    @McpTool(name = "db_describe_table", description = "Inspect a table's column names, types, nullability, and primary key.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Map<String, Object> describeTable(
            @McpToolParam(description = "Exact table name", required = true) String tableName) {
        return service.describeTable(tableName);
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
