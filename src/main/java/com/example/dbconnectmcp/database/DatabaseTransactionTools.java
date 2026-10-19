package com.example.dbconnectmcp.database;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** The only agent-supplied arguments are SQL and the optional transaction handle. */
@Component
public class DatabaseTransactionTools {
    private final DatabaseTransactionManager transactions;
    private final JsonMapper json = JsonMapper.builder().build();

    public DatabaseTransactionTools(DatabaseTransactionManager transactions) { this.transactions = transactions; }

    @McpTool(name = "db_execute", description = "Explicit agent-controlled transaction SQL. Send BEGIN without transactionId "
            + "to obtain an ID, then pass it on every query/DML/script and COMMIT or ROLLBACK. The ID belongs to this MCP session. "
            + "Queries see this transaction's own writes. No automatic commit; execution errors require ROLLBACK. "
            + "TRUNCATE is allowed; other DDL, CALL, dynamic SQL, and session-control commands are rejected. "
            + "WARNING: MySQL/Oracle TRUNCATE may commit earlier writes and cannot be rolled back. "
            + "Transactions pin their original data source across switches and expire when idle. "
            + "Only sql and transactionId are accepted; use SQL literals, no parameter array. Read skill.md for the full workflow.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true,
                    idempotentHint = false, openWorldHint = true))
    public CallToolResult execute(McpSyncRequestContext context,
            @McpToolParam(description = "BEGIN; a query/DML/TRUNCATE script; or standalone COMMIT/ROLLBACK", required = true) String sql,
            @McpToolParam(description = "ID returned by BEGIN. Required except when opening a new transaction.", required = false) String transactionId) {
        // Use the SDK's verified exchange context, not an agent-supplied session field.
        // Context parameters are injected by Spring AI and excluded from inputSchema.
        var result = transactions.execute(context.sessionId(), sql, transactionId);
        return CallToolResult.builder().isError(!Boolean.TRUE.equals(result.get("success")))
                .addTextContent(json.writeValueAsString(result)).build();
    }
}
