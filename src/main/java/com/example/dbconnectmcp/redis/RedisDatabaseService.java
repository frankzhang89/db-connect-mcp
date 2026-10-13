package com.example.dbconnectmcp.redis;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Holds the current Redis database. Spring Data Redis rejects SELECT on a
 * shared Lettuce connection, so every database (including the default one from
 * {@code spring.data.redis.database}) gets its own lazily created connection
 * factory. Tool limits such as {@code dbmcp.redis.max-items} are optional
 * properties with sensible defaults.
 */
@Service
public class RedisDatabaseService implements DisposableBean {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final int defaultDb;
    private final int maxDatabases;
    // One lazily created factory/template per database index.
    private final ConcurrentHashMap<Integer, LettuceConnectionFactory> factories = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, StringRedisTemplate> templates = new ConcurrentHashMap<>();

    private volatile int currentDb;

    public RedisDatabaseService(
            @Value("${spring.data.redis.database:0}") int defaultDb,
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.username:}") String username,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${dbmcp.redis.max-databases:16}") int maxDatabases) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.maxDatabases = Math.max(1, maxDatabases);
        if (defaultDb < 0 || defaultDb >= this.maxDatabases) {
            throw new IllegalArgumentException("spring.data.redis.database must be between 0 and " + (this.maxDatabases - 1) + ".");
        }
        this.defaultDb = defaultDb;
        this.currentDb = defaultDb;
    }

    public int currentDatabase() {
        return currentDb;
    }

    public StringRedisTemplate currentTemplate() {
        return templateFor(currentDb);
    }

    public Map<String, Object> selectDatabase(Integer db) {
        if (db == null || db < 0 || db >= maxDatabases) {
            throw new IllegalArgumentException("The database index must be between 0 and " + (maxDatabases - 1) + ".");
        }
        // Build (or reuse) the template for the target database.
        StringRedisTemplate target = templateFor(db);
        // Verify reachability and credentials with PING before switching, so a
        // failed switch leaves the current database untouched.
        try {
            target.execute((RedisCallback<String>) connection -> connection.ping());
        }
        catch (RuntimeException ex) {
            throw new IllegalStateException("Failed to switch to database " + db + ": " + ex.getMessage());
        }
        currentDb = db;
        return Map.of("database", db);
    }

    private StringRedisTemplate templateFor(int db) {
        // computeIfAbsent guarantees a single factory per database index.
        return templates.computeIfAbsent(db, this::createTemplate);
    }

    private StringRedisTemplate createTemplate(int db) {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
        configuration.setDatabase(db);
        // Apply the shared credentials only when they are actually set; an
        // empty password would otherwise trigger a failing AUTH command.
        if (username != null && !username.isBlank()) {
            configuration.setUsername(username);
        }
        if (password != null && !password.isBlank()) {
            configuration.setPassword(RedisPassword.of(password));
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        // Track the factory so destroy() can shut it down with the application.
        factories.put(db, factory);
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    @Override
    public void destroy() {
        // Close every dynamically created factory; the auto-configured one is
        // managed by Spring itself.
        factories.values().forEach(LettuceConnectionFactory::destroy);
        factories.clear();
        templates.clear();
    }
}
