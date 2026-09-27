package com.example.dbconnectmcp.database;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

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
}
