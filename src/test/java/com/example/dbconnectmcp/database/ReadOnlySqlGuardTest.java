package com.example.dbconnectmcp.database;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReadOnlySqlGuardTest {

    private final ReadOnlySqlGuard guard = new ReadOnlySqlGuard();

    @Test
    void acceptsSelectAndReadOnlyCte() {
        assertDoesNotThrow(() -> guard.validate("SELECT id FROM users WHERE id = ?"));
        assertDoesNotThrow(() -> guard.validate("WITH recent AS (SELECT id FROM users) SELECT id FROM recent"));
    }

    @Test
    void rejectsWritesAndMultipleStatements() {
        assertThrows(IllegalArgumentException.class, () -> guard.validate("UPDATE users SET name = 'x'"));
        assertThrows(IllegalArgumentException.class, () -> guard.validate("CREATE TABLE secret (id INT)"));
        assertThrows(IllegalArgumentException.class, () -> guard.validate("SELECT 1; DELETE FROM users"));
        assertThrows(IllegalArgumentException.class, () -> guard.validate("SELECT * FROM users FOR UPDATE"));
        assertThrows(IllegalArgumentException.class, () -> guard.validate("SELECT * FROM users FOR SHARE"));
        assertThrows(IllegalArgumentException.class, () -> guard.validate("SELECT 1 INTO OUTFILE '/tmp/x'"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT name FROM employees WHERE department_id = ? FETCH FIRST 5 ROWS ONLY",
            "SELECT TOP 5 id FROM [dbo].[users] WHERE [name] = ?",
            "SELECT id, payload->>'name' FROM public.events WHERE id = ? LIMIT 5",
            "SELECT values_array[1] FROM public.events WHERE id = ?",
            "SELECT id FROM default_catalog.demo.events WHERE id = ? LIMIT 5",
            "SELECT 1 FROM dual",
            "SELECT * FROM (WITH recent AS (SELECT id FROM users) SELECT id FROM recent) r"
    })
    void acceptsQueriesAcrossSupportedDatabases(String sql) {
        assertDoesNotThrow(() -> guard.validate(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT id INTO copied_users FROM users",
            "WITH deleted AS (DELETE FROM users RETURNING id) SELECT id FROM deleted",
            "SELECT * FROM (WITH deleted AS (DELETE FROM users RETURNING id) SELECT id FROM deleted) d",
            "SELECT nextval('user_seq')",
            "SELECT user_seq.NEXTVAL FROM dual",
            "SELECT NEXT VALUE FOR user_seq",
            "SELECT setval('user_seq', 100)",
            "SELECT pg_advisory_lock(1)",
            "SELECT pg_sleep(10)",
            "SELECT * FROM users FOR NO KEY UPDATE",
            "SELECT * FROM users FOR KEY SHARE",
            "SELECT * FROM users WITH (UPDLOCK)"
    })
    void rejectsDialectSpecificWritesAndSideEffects(String sql) {
        assertThrows(IllegalArgumentException.class, () -> guard.validate(sql));
    }
}
