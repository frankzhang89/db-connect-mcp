package com.example.dbconnectmcp.database;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DatabaseDialectRegistry {

    private static final Map<String, String> PLANNED_TYPES = Map.of(
            "jdbc:oracle:", "Oracle",
            "jdbc:sqlserver:", "SQL Server",
            "jdbc:postgresql:", "PostgreSQL",
            "jdbc:starrocks:", "StarRocks");

    private final List<DatabaseDialect> dialects;

    public DatabaseDialectRegistry(List<DatabaseDialect> dialects) {
        this.dialects = List.copyOf(dialects);
    }

    public DatabaseDialect resolve(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("缺少 dbmcp.database.url，请在私有 YAML 中配置 JDBC 地址。");
        }
        for (DatabaseDialect dialect : dialects) {
            if (url.startsWith(dialect.urlPrefix())) {
                return dialect;
            }
        }
        for (var planned : PLANNED_TYPES.entrySet()) {
            if (url.startsWith(planned.getKey())) {
                throw new IllegalArgumentException(planned.getValue() + " 已识别，但第一版尚未提供适配器。");
            }
        }
        throw new IllegalArgumentException("不支持的 JDBC 地址前缀。");
    }
}
