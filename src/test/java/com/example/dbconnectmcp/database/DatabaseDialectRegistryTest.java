package com.example.dbconnectmcp.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DatabaseDialectRegistryTest {

    private final DatabaseDialectRegistry registry = new DatabaseDialectRegistry(List.of(new MySqlDialect()));

    @Test
    void selectsMySqlByJdbcPrefixWithoutReturningUrl() {
        assertEquals("MySQL", registry.resolve("jdbc:mysql://localhost:3306/demo").name());
    }

    @Test
    void recognizesPlannedTypesWithoutPretendingTheyWork() {
        for (String url : List.of("jdbc:oracle:thin:@localhost", "jdbc:sqlserver://localhost",
                "jdbc:postgresql://localhost/demo", "jdbc:starrocks://localhost/demo")) {
            String message = assertThrows(IllegalArgumentException.class, () -> registry.resolve(url)).getMessage();
            assertTrue(message.contains("尚未提供适配器"));
            assertFalse(message.contains(url));
        }
    }

    @Test
    void rejectsUnknownPrefixWithoutExposingConnectionString() {
        String url = "jdbc:unknown://private-host/demo?password=secret";
        String message = assertThrows(IllegalArgumentException.class, () -> registry.resolve(url)).getMessage();
        assertFalse(message.contains("private-host"));
        assertFalse(message.contains("secret"));
    }
}
