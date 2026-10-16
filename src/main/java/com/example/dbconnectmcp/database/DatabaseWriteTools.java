package com.example.dbconnectmcp.database;

import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Write tools use the same current global JDBC target as the read tools.
 * DDL can be turned off with
 * {@code dbmcp.write.allow-ddl=false}; DML is always available.
 */
@Component
public class DatabaseWriteTools {

    private final DatabaseWriteService service;

    public DatabaseWriteTools(DatabaseWriteService service) {
        this.service = service;
    }

    @McpTool(name = "db_execute_dml", description = "Run one INSERT, UPDATE, DELETE, MERGE, or UPSERT supported by the configured database; use ? placeholders and parameters. Returns affected rows and, for MySQL/SQL Server INSERT, at most 50 generated keys. RETURNING/OUTPUT are not supported.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false))
    public Map<String, Object> executeDml(
            @McpToolParam(description = "Single DML statement", required = true) String sql,
            @McpToolParam(description = "Scalar parameters in ? order, optional", required = false) List<Object> parameters) {
        return service.executeDml(sql, parameters);
    }

    @McpTool(name = "db_execute_ddl", description = "Run one CREATE, ALTER, DROP, TRUNCATE, or RENAME statement. Destructive and irreversible (DROP, TRUNCATE); confirm the target object before calling.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false))
    public Map<String, Object> executeDdl(
            @McpToolParam(description = "Single DDL statement", required = true) String sql) {
        return service.executeDdl(sql);
    }
}
