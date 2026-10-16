package com.example.dbconnectmcp.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.sql.Driver;
import java.sql.DriverManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.Test;

class DatabaseDialectRegistryTest {

    private final DatabaseDialectRegistry registry = new DatabaseDialectRegistry(List.of(new MySqlDialect(),
            new OracleDialect(), new SqlServerDialect(), new PostgreSqlDialect(), new StarRocksDialect()));

    @Test
    void selectsMySqlByJdbcPrefixWithoutReturningUrl() {
        assertEquals("MySQL", registry.resolve("jdbc:mysql://localhost:3306/demo").name());
    }

    @ParameterizedTest
    @CsvSource({
            "jdbc:mysql://localhost:3306/demo, MySQL, com.mysql.cj.jdbc.Driver",
            "jdbc:oracle:thin:@//localhost:1521/FREEPDB1, Oracle, oracle.jdbc.OracleDriver",
            "jdbc:sqlserver://localhost:1433;databaseName=demo, SQL Server, com.microsoft.sqlserver.jdbc.SQLServerDriver",
            "jdbc:postgresql://localhost:5432/demo, PostgreSQL, org.postgresql.Driver",
            "jdbc:starrocks://localhost:9030/default_catalog.demo, StarRocks, com.starrocks.cj.jdbc.Driver"
    })
    void resolvesAndLoadsRealDriverWithoutConnecting(String url, String name, String driverClass) throws Exception {
        DatabaseDialect dialect = registry.resolve(url);
        assertEquals(name, dialect.name());
        assertEquals(driverClass, dialect.driverClassName());
        Driver driver = (Driver) Class.forName(driverClass).getDeclaredConstructor().newInstance();
        assertTrue(driver.acceptsURL(url));
        assertEquals(driverClass, DriverManager.getDriver(url).getClass().getName());
    }

    @Test
    void discoversAllAdaptersThroughSpring() {
        // Register only the dialect components: no data source or credentials needed.
        try (var context = new AnnotationConfigApplicationContext(DatabaseDialectRegistry.class, MySqlDialect.class,
                OracleDialect.class, SqlServerDialect.class, PostgreSqlDialect.class, StarRocksDialect.class)) {
            assertEquals(5, context.getBeansOfType(DatabaseDialect.class).size());
            assertEquals("StarRocks", context.getBean(DatabaseDialectRegistry.class)
                    .resolve("jdbc:starrocks://localhost:9030/default_catalog.demo").name());
        }
    }

    @Test
    void rejectsEmptyConnectionAddresses() {
        assertThrows(IllegalArgumentException.class, () -> registry.resolve(null));
        assertThrows(IllegalArgumentException.class, () -> registry.resolve(" "));
    }

    @Test
    void rejectsUnknownPrefixWithoutExposingConnectionString() {
        String url = "jdbc:unknown://private-host/demo?password=secret";
        String message = assertThrows(IllegalArgumentException.class, () -> registry.resolve(url)).getMessage();
        assertFalse(message.contains("private-host"));
        assertFalse(message.contains("secret"));
    }
}
