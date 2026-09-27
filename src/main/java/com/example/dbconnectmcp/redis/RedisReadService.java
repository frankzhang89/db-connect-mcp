package com.example.dbconnectmcp.redis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

@Service
public class RedisReadService {

    private final StringRedisTemplate redis;
    private final int maxScanCount;
    private final int maxItems;
    private final int maxValueChars;

    public RedisReadService(StringRedisTemplate redis,
            @Value("${dbmcp.redis.max-scan-count:100}") int maxScanCount,
            @Value("${dbmcp.redis.max-items:100}") int maxItems,
            @Value("${dbmcp.redis.max-value-chars:4000}") int maxValueChars) {
        this.redis = redis;
        this.maxScanCount = Math.max(1, maxScanCount);
        this.maxItems = Math.max(1, maxItems);
        this.maxValueChars = Math.max(1, maxValueChars);
    }

    public Map<String, Object> scanKeys(String pattern, String cursor, Integer requestedCount) {
        return safely(() -> {
            String match = pattern == null || pattern.isBlank() ? "*" : pattern;
            if (match.length() > 256) {
                throw new IllegalArgumentException("Redis 匹配模式过长。");
            }
            String position = cursor == null || cursor.isBlank() ? "0" : cursor;
            if (!position.matches("[0-9]{1,20}")) {
                throw new IllegalArgumentException("Redis 游标必须是非负整数。");
            }
            int count = bound(requestedCount, maxScanCount);
            Object response = redis.execute((RedisCallback<Object>) connection -> connection.execute("SCAN",
                    bytes(position), bytes("MATCH"), bytes(match), bytes("COUNT"), bytes(Integer.toString(count))));
            if (!(response instanceof List<?> parts) || parts.size() != 2 || !(parts.get(1) instanceof List<?> rawKeys)) {
                throw new IllegalStateException("Redis SCAN 返回了无法识别的结果。");
            }
            List<String> keys = new ArrayList<>();
            for (Object rawKey : rawKeys) {
                keys.add(decode(rawKey));
            }
            return Map.of("keys", keys, "nextCursor", decode(parts.get(0)), "count", keys.size());
        });
    }

    public Map<String, Object> inspectKey(String key) {
        return safely(() -> {
            requireKey(key);
            DataType type = redis.type(key);
            if (type == null || type == DataType.NONE) {
                return Map.of("key", key, "exists", false);
            }
            Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
            Long size = switch (type) {
                case STRING -> redis.opsForValue().size(key);
                case HASH -> redis.opsForHash().size(key);
                case LIST -> redis.opsForList().size(key);
                case SET -> redis.opsForSet().size(key);
                case ZSET -> redis.opsForZSet().size(key);
                default -> null;
            };
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("key", key);
            result.put("exists", true);
            result.put("type", type.code());
            result.put("ttlSeconds", ttl);
            result.put("size", size);
            return result;
        });
    }

    public Map<String, Object> readKey(String key, Integer requestedItems) {
        return safely(() -> {
            requireKey(key);
            int limit = bound(requestedItems, maxItems);
            DataType type = redis.type(key);
            if (type == null || type == DataType.NONE) {
                return Map.of("key", key, "exists", false);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("key", key);
            result.put("type", type.code());
            switch (type) {
                case STRING -> {
                    Long length = redis.opsForValue().size(key);
                    String value = redis.opsForValue().get(key, 0, maxValueChars - 1L);
                    result.put("value", value);
                    result.put("truncated", length != null && length > maxValueChars);
                }
                case LIST -> {
                    Long length = redis.opsForList().size(key);
                    result.put("items", shorten(redis.opsForList().range(key, 0, limit - 1L)));
                    result.put("truncated", length != null && length > limit);
                }
                case HASH -> {
                    List<Map<String, Object>> items = new ArrayList<>();
                    try (Cursor<Map.Entry<Object, Object>> scan = redis.opsForHash().scan(key, scanOptions(limit))) {
                        while (scan.hasNext() && items.size() < limit) {
                            var entry = scan.next();
                            items.add(Map.of("field", shorten(String.valueOf(entry.getKey())),
                                    "value", shorten(String.valueOf(entry.getValue()))));
                        }
                    }
                    result.put("items", items);
                    result.put("truncated", redis.opsForHash().size(key) > items.size());
                }
                case SET -> {
                    List<String> items = new ArrayList<>();
                    try (Cursor<String> scan = redis.opsForSet().scan(key, scanOptions(limit))) {
                        while (scan.hasNext() && items.size() < limit) {
                            items.add(shorten(scan.next()));
                        }
                    }
                    result.put("items", items);
                    result.put("truncated", redis.opsForSet().size(key) > items.size());
                }
                case ZSET -> {
                    List<Map<String, Object>> items = new ArrayList<>();
                    try (Cursor<ZSetOperations.TypedTuple<String>> scan = redis.opsForZSet().scan(key, scanOptions(limit))) {
                        while (scan.hasNext() && items.size() < limit) {
                            var entry = scan.next();
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("value", shorten(entry.getValue()));
                            item.put("score", entry.getScore());
                            items.add(item);
                        }
                    }
                    result.put("items", items);
                    result.put("truncated", redis.opsForZSet().size(key) > items.size());
                }
                default -> throw new IllegalArgumentException("第一版不支持读取此 Redis 类型。");
            }
            return result;
        });
    }

    private ScanOptions scanOptions(int count) {
        return ScanOptions.scanOptions().count(count).build();
    }

    private List<String> shorten(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(this::shorten).toList();
    }

    private String shorten(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxValueChars ? value : value.substring(0, maxValueChars);
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 512) {
            throw new IllegalArgumentException("Redis key 不能为空且长度不能超过 512 个字符。");
        }
    }

    private int bound(Integer requested, int maximum) {
        return requested == null ? maximum : Math.min(Math.max(1, requested), maximum);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private String decode(Object value) {
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
    }

    private Map<String, Object> safely(Supplier<Map<String, Object>> operation) {
        try {
            return operation.get();
        }
        catch (IllegalArgumentException ex) {
            throw ex;
        }
        catch (RuntimeException ex) {
            throw new IllegalStateException("Redis 读取失败，请检查服务状态及只读权限。");
        }
    }
}
