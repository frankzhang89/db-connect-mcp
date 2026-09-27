package com.example.dbconnectmcp.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DatabaseProperties.class)
public class DatabaseConfiguration {

    @Bean
    public DataSource dataSource(DatabaseProperties properties, DatabaseDialectRegistry registry) {
        if (properties.username() == null || properties.username().isBlank() || properties.password() == null) {
            throw new IllegalArgumentException("缺少数据库账号或密码，请在私有 YAML 中配置。");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.url());
        config.setUsername(properties.username());
        config.setPassword(properties.password());
        config.setDriverClassName(registry.resolve(properties.url()).driverClassName());
        config.setPoolName("db-connect-mcp-readonly");
        config.setReadOnly(true);
        config.setMaximumPoolSize(3);
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }
}
