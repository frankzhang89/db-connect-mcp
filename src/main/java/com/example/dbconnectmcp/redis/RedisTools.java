package com.example.dbconnectmcp.redis;

import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Redis tools exposed over MCP. All of them operate on the database tracked
 * by {@link RedisDatabaseService}, so {@code redis_select_db} affects reads
 * and writes alike.
 * Redis selection changes a logical database index on the configured host; JDBC
 * target replacement is exposed separately by DatabaseConnectionTools.
 */
@Component
public class RedisTools {

    private final RedisReadService service;
    private final RedisWriteService writeService;
    private final RedisDatabaseService databases;

    public RedisTools(RedisReadService service, RedisWriteService writeService, RedisDatabaseService databases) {
        this.service = service;
        this.writeService = writeService;
        this.databases = databases;
    }

    @McpTool(name = "redis_scan_keys", description = "List keys in the current Redis database page by page using SCAN (never KEYS); pass the returned nextCursor back to continue.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Map<String, Object> scanKeys(
            @McpToolParam(description = "Redis glob pattern, defaults to *", required = false) String pattern,
            @McpToolParam(description = "nextCursor from the previous call, start with 0", required = false) String cursor,
            @McpToolParam(description = "COUNT hint per scan, optional", required = false) Integer count) {
        return service.scanKeys(pattern, cursor, count);
    }

    @McpTool(name = "redis_inspect_key", description = "Read a key's type, TTL, and size in the current Redis database without modifying data; reports exists=false for missing keys.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Map<String, Object> inspectKey(
            @McpToolParam(description = "Redis key to inspect", required = true) String key) {
        return service.inspectKey(key);
    }

    @McpTool(name = "redis_read_key", description = "Read a limited amount of data from a string, hash, list, set, or sorted set key in the current database; at most 100 elements per call.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Map<String, Object> readKey(
            @McpToolParam(description = "Redis key to read", required = true) String key,
            @McpToolParam(description = "Maximum number of elements, optional", required = false) Integer maxItems) {
        return service.readKey(key, maxItems);
    }

    @McpTool(name = "redis_write_key", description = "Write a string, list, set, hash, or zset key in the current database. Lists append to the tail, hashes merge incrementally, and an optional TTL in seconds can be set.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false))
    public Map<String, Object> writeKey(
            @McpToolParam(description = "Redis key", required = true) String key,
            @McpToolParam(description = "Type: string, list, set, hash, or zset", required = true) String type,
            @McpToolParam(description = "Value for the string type", required = false) String value,
            @McpToolParam(description = "Member list for the list and set types", required = false) List<String> values,
            @McpToolParam(description = "Field-to-value mapping for the hash type", required = false) Map<String, String> fields,
            @McpToolParam(description = "Member-to-score mapping for the zset type", required = false) Map<String, Double> scoreMembers,
            @McpToolParam(description = "Expiry in seconds, optional", required = false) Integer ttlSeconds) {
        return writeService.writeKey(key, type, value, values, fields, scoreMembers, ttlSeconds);
    }

    @McpTool(name = "redis_delete_keys", description = "Delete one or more keys in the current Redis database (at most 100 per call) and report how many were actually removed.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false))
    public Map<String, Object> deleteKeys(
            @McpToolParam(description = "List of keys to delete", required = true) List<String> keys) {
        return writeService.deleteKeys(keys);
    }

    @McpTool(name = "redis_expire_key", description = "Set or remove a key's TTL in the current database; pass 0 for ttlSeconds to remove the expiry (PERSIST).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public Map<String, Object> expireKey(
            @McpToolParam(description = "Redis key", required = true) String key,
            @McpToolParam(description = "Expiry in seconds; 0 removes the expiry", required = true) Integer ttlSeconds) {
        return writeService.expireKey(key, ttlSeconds);
    }

    @McpTool(name = "redis_select_db", description = "Switch the current Redis database (SELECT db) for all subsequent Redis tools, both reads and writes; the default database comes from spring.data.redis.database.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public Map<String, Object> selectDb(
            @McpToolParam(description = "Target database index (0 to 15 by default)", required = true) Integer db) {
        return databases.selectDatabase(db);
    }
}
