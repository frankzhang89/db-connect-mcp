package com.example.dbconnectmcp.redis;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

/**
 * Write access to the currently selected Redis database. Input sizes are
 * validated up front (maxItems elements, maxValueChars per element): reads
 * truncate, but writes with oversized values are rejected outright so data is
 * never silently cut.
 */
@Service
public class RedisWriteService {

    private final RedisDatabaseService databases;
    private final int maxItems;
    private final int maxValueChars;

    public RedisWriteService(RedisDatabaseService databases,
            @Value("${dbmcp.redis.max-items:100}") int maxItems,
            @Value("${dbmcp.redis.max-value-chars:4000}") int maxValueChars) {
        this.databases = databases;
        this.maxItems = Math.max(1, maxItems);
        this.maxValueChars = Math.max(1, maxValueChars);
    }

    /** Applies type-specific writes and then an optional expiry using one captured template. */
    public Map<String, Object> writeKey(String key, String type, String value, List<String> values,
            Map<String, String> fields, Map<String, Double> scoreMembers, Integer ttlSeconds) {
        return safely(() -> {
            requireKey(key);
            String normalizedType = type == null ? "" : type.strip().toLowerCase(Locale.ROOT);
            // Every call writes through the template of the currently selected database.
            StringRedisTemplate redis = databases.currentTemplate();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("key", key);
            result.put("type", normalizedType);
            // Dispatch on the requested type; each branch validates its own payload.
            switch (normalizedType) {
                case "string" -> {
                    if (value == null) {
                        throw new IllegalArgumentException("The string type requires the value parameter.");
                    }
                    checkText(value, "The string value");
                    // SET replaces the whole string value.
                    redis.opsForValue().set(key, value);
                    result.put("written", 1);
                }
                case "list" -> {
                    List<String> items = requireValues(values);
                    // RPUSH appends to the tail; existing elements are kept.
                    Long pushed = redis.opsForList().rightPushAll(key, items);
                    result.put("pushed", pushed == null ? 0 : pushed);
                }
                case "set" -> {
                    List<String> members = requireValues(values);
                    // SADD ignores duplicates; the count covers new members only.
                    Long added = redis.opsForSet().add(key, members.toArray(String[]::new));
                    result.put("added", added == null ? 0 : added);
                }
                case "hash" -> {
                    Map<String, String> entries = requireFields(fields);
                    // HSET merges field by field; other fields are kept.
                    redis.opsForHash().putAll(key, entries);
                    result.put("written", entries.size());
                }
                case "zset" -> {
                    Map<String, Double> entries = requireScores(scoreMembers);
                    // ZADD upserts the score of each member.
                    Set<ZSetOperations.TypedTuple<String>> tuples = entries.entrySet().stream()
                            .map(entry -> new DefaultTypedTuple<>(entry.getKey(), entry.getValue()))
                            .collect(Collectors.toSet());
                    Long written = redis.opsForZSet().add(key, tuples);
                    result.put("written", written == null ? tuples.size() : written);
                }
                default -> throw new IllegalArgumentException(
                        "Unsupported type: " + normalizedType + "; valid types are string, list, set, hash, and zset.");
            }
            // Expiry is a separate command after the write, not an atomic transaction.
            // An invalid TTL or expiry failure does not undo data already written.
            if (ttlSeconds != null) {
                if (ttlSeconds <= 0) {
                    throw new IllegalArgumentException("ttlSeconds must be greater than 0.");
                }
                redis.expire(key, java.time.Duration.ofSeconds(ttlSeconds));
                result.put("ttlSeconds", ttlSeconds);
            }
            return result;
        });
    }

    public Map<String, Object> deleteKeys(List<String> keys) {
        return safely(() -> {
            if (keys == null || keys.isEmpty() || keys.size() > maxItems) {
                throw new IllegalArgumentException("Provide between 1 and " + maxItems + " keys per call.");
            }
            keys.forEach(this::requireKey);
            // DEL reports only the keys that actually existed.
            Long deleted = databases.currentTemplate().delete(new HashSet<>(keys));
            return Map.of("requested", keys.size(), "deleted", deleted == null ? 0 : deleted);
        });
    }

    public Map<String, Object> expireKey(String key, Integer ttlSeconds) {
        return safely(() -> {
            requireKey(key);
            if (ttlSeconds == null || ttlSeconds < 0) {
                throw new IllegalArgumentException("ttlSeconds must not be negative; 0 removes the expiry.");
            }
            StringRedisTemplate redis = databases.currentTemplate();
            if (ttlSeconds == 0) {
                // PERSIST removes the expiry; false means the key is missing or has no expiry.
                Boolean persisted = redis.persist(key);
                return Map.of("key", key, "persisted", Boolean.TRUE.equals(persisted));
            }
            Boolean applied = redis.expire(key, java.time.Duration.ofSeconds(ttlSeconds));
            return Map.of("key", key, "ttlSeconds", ttlSeconds, "applied", Boolean.TRUE.equals(applied));
        });
    }

    private List<String> requireValues(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > maxItems) {
            throw new IllegalArgumentException("values must contain between 1 and " + maxItems + " elements.");
        }
        for (String item : values) {
            if (item == null) {
                throw new IllegalArgumentException("values must not contain null.");
            }
            checkText(item, "The list/set member");
        }
        return values;
    }

    private Map<String, String> requireFields(Map<String, String> fields) {
        if (fields == null || fields.isEmpty() || fields.size() > maxItems) {
            throw new IllegalArgumentException("fields must contain between 1 and " + maxItems + " entries.");
        }
        for (var entry : fields.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                throw new IllegalArgumentException("Field names and values in fields must not be empty.");
            }
            checkText(entry.getKey(), "The hash field name");
            checkText(entry.getValue(), "The hash field value");
        }
        return fields;
    }

    private Map<String, Double> requireScores(Map<String, Double> scoreMembers) {
        if (scoreMembers == null || scoreMembers.isEmpty() || scoreMembers.size() > maxItems) {
            throw new IllegalArgumentException("scoreMembers must contain between 1 and " + maxItems + " entries.");
        }
        for (var entry : scoreMembers.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("Members in scoreMembers must not be empty.");
            }
            // NaN and infinity cannot be stored as zset scores.
            if (entry.getValue() == null || !Double.isFinite(entry.getValue())) {
                throw new IllegalArgumentException("Scores in scoreMembers must be finite numbers.");
            }
            checkText(entry.getKey(), "The sorted-set member");
        }
        return scoreMembers;
    }

    private void checkText(String text, String name) {
        if (text.length() > maxValueChars) {
            throw new IllegalArgumentException(name + " must not exceed " + maxValueChars + " characters.");
        }
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 512) {
            throw new IllegalArgumentException("The Redis key must not be empty and must not exceed 512 characters.");
        }
    }

    private Map<String, Object> safely(Supplier<Map<String, Object>> operation) {
        try {
            return operation.get();
        }
        catch (IllegalArgumentException | IllegalStateException ex) {
            throw ex;
        }
        catch (RuntimeException ex) {
            throw new IllegalStateException("Redis write failed: " + ex.getMessage());
        }
    }
}
