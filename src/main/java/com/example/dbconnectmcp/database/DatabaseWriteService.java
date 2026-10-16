package com.example.dbconnectmcp.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Write access shares the connection pool with {@link DatabaseReadService};
 * Dialect policies prepare read sessions and restore writable sessions.
 */
@Service
public class DatabaseWriteService {

    // Cap on the auto-generated keys returned for INSERT statements.
    private static final int MAX_GENERATED_KEYS = 50;

    private final DataSource dataSource;
    private final WriteSqlGuard sqlGuard;
    private final DatabaseDialect dialect;
    private final boolean allowDdl;
    private final int timeoutSeconds;

    public DatabaseWriteService(DataSource dataSource, WriteSqlGuard sqlGuard,
            DatabaseProperties properties, DatabaseDialectRegistry registry,
            @Value("${dbmcp.write.allow-ddl:true}") boolean allowDdl,
            @Value("${dbmcp.query.timeout-seconds:10}") int timeoutSeconds) {
        this.dataSource = dataSource;
        this.sqlGuard = sqlGuard;
        this.dialect = registry.resolve(properties.url());
        this.allowDdl = allowDdl;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    public Map<String, Object> executeDml(String sql, List<Object> parameters) {
        // Whitelist validation happens before any connection is opened.
        sqlGuard.validateDml(sql);
        List<Object> args = parameters == null ? List.of() : parameters;
        try (Connection connection = dataSource.getConnection()) {
            // Clear the read-only session flag so the statement can modify data.
            dialect.prepareWrite(connection);
            boolean returnKeys = dialect.supportsGeneratedKeys() && sqlGuard.isInsert(sql);
            try (PreparedStatement statement = returnKeys
                    ? connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
                    : connection.prepareStatement(sql)) {
                statement.setQueryTimeout(timeoutSeconds);
                bindParameters(statement, args);
                int affectedRows = statement.executeUpdate();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("affectedRows", affectedRows);
                if (returnKeys) {
                    collectGeneratedKeys(statement, result);
                }
                return result;
            }
        }
        catch (SQLException ex) {
            throw databaseFailure("Database write failed", ex);
        }
    }

    public Map<String, Object> executeDdl(String sql) {
        // DDL can be disabled independently of DML via dbmcp.write.allow-ddl.
        if (!allowDdl) {
            throw new IllegalArgumentException("The DDL tool is disabled (dbmcp.write.allow-ddl=false).");
        }
        sqlGuard.validateDdl(sql);
        try (Connection connection = dataSource.getConnection()) {
            dialect.prepareWrite(connection);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                statement.execute(sql);
                return Map.of("success", true);
            }
        }
        catch (SQLException ex) {
            throw databaseFailure("DDL execution failed", ex);
        }
    }

    private void bindParameters(PreparedStatement statement, List<Object> args) throws SQLException {
        // Only scalars are accepted to avoid driver-specific object binding.
        for (int i = 0; i < args.size(); i++) {
            Object arg = args.get(i);
            if (arg != null && !(arg instanceof String || arg instanceof Number || arg instanceof Boolean)) {
                throw new IllegalArgumentException("SQL parameters may only be strings, numbers, booleans, or null.");
            }
            statement.setObject(i + 1, arg);
        }
    }

    private void collectGeneratedKeys(PreparedStatement statement, Map<String, Object> result) throws SQLException {
        // Auto-increment ids of an INSERT; capped to keep responses small.
        List<Object> keys = new ArrayList<>();
        boolean truncated = false;
        try (ResultSet generated = statement.getGeneratedKeys()) {
            while (generated.next()) {
                if (keys.size() >= MAX_GENERATED_KEYS) {
                    truncated = true;
                    break;
                }
                keys.add(generated.getObject(1));
            }
        }
        if (!keys.isEmpty()) {
            result.put("generatedKeys", keys);
            if (truncated) {
                result.put("generatedKeysTruncated", true);
            }
        }
    }

    private IllegalStateException databaseFailure(String action, SQLException ex) {
        String sqlState = ex.getSQLState();
        return new IllegalStateException(action + (sqlState == null ? "." : " (SQLState: " + sqlState + ")."));
    }
}
