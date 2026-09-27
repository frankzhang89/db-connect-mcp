package com.example.dbconnectmcp.redis;

import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class RedisTools {

    private final RedisReadService service;

    public RedisTools(RedisReadService service) {
        this.service = service;
    }

    @McpTool(name = "redis_scan_keys", description = "使用 SCAN 分页列出 Redis key，不使用 KEYS 命令。",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Map<String, Object> scanKeys(
            @McpToolParam(description = "Redis glob 匹配模式，默认 *", required = false) String pattern,
            @McpToolParam(description = "上次返回的 nextCursor，首次使用 0", required = false) String cursor,
            @McpToolParam(description = "单次扫描的 COUNT 提示值", required = false) Integer count) {
        return service.scanKeys(pattern, cursor, count);
    }

    @McpTool(name = "redis_inspect_key", description = "读取 Redis key 的类型、TTL 和大小，不修改数据。",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Map<String, Object> inspectKey(
            @McpToolParam(description = "要检查的 Redis key", required = true) String key) {
        return service.inspectKey(key);
    }

    @McpTool(name = "redis_read_key", description = "限量读取 string、hash、list、set 或 sorted set 内容。",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Map<String, Object> readKey(
            @McpToolParam(description = "要读取的 Redis key", required = true) String key,
            @McpToolParam(description = "最大元素数，可省略", required = false) Integer maxItems) {
        return service.readKey(key, maxItems);
    }
}
