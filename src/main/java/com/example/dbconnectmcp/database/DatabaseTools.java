package com.example.dbconnectmcp.database;

import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class DatabaseTools {

    private final DatabaseReadService service;

    public DatabaseTools(DatabaseReadService service) {
        this.service = service;
    }

    @McpTool(name = "db_list_tables", description = "列出已配置 MySQL 数据库中的表和视图，最多返回 500 个。",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Map<String, Object> listTables() {
        return service.listTables();
    }

    @McpTool(name = "db_describe_table", description = "查看指定表的字段类型、可空性和主键。",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Map<String, Object> describeTable(
            @McpToolParam(description = "精确表名", required = true) String tableName) {
        return service.describeTable(tableName);
    }

    @McpTool(name = "db_query", description = "执行单条只读 SELECT 查询；使用 ? 占位符和 parameters 参数绑定值。",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Map<String, Object> query(
            @McpToolParam(description = "只读 SELECT SQL", required = true) String sql,
            @McpToolParam(description = "按 ? 顺序排列的标量参数，可省略", required = false) List<Object> parameters,
            @McpToolParam(description = "请求返回的最大行数，可省略", required = false) Integer maxRows) {
        return service.query(sql, parameters, maxRows);
    }
}
