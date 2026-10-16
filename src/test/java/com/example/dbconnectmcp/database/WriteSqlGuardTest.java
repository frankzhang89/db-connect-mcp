package com.example.dbconnectmcp.database;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WriteSqlGuardTest {

    private final WriteSqlGuard guard = new WriteSqlGuard();

    @Test
    void acceptsInsertUpdateDelete() {
        assertDoesNotThrow(() -> guard.validateDml("INSERT INTO t (a, b) VALUES (?, ?)"));
        assertDoesNotThrow(() -> guard.validateDml("UPDATE t SET a = ? WHERE id = ?"));
        assertDoesNotThrow(() -> guard.validateDml("DELETE FROM t WHERE id = ?"));
    }

    @Test
    void rejectsSelectAndDdlInDmlTool() {
        assertThrows(IllegalArgumentException.class, () -> guard.validateDml("SELECT * FROM t"));
        assertThrows(IllegalArgumentException.class, () -> guard.validateDml("DROP TABLE t"));
    }

    @Test
    void acceptsCommonDdl() {
        assertDoesNotThrow(() -> guard.validateDdl("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50))"));
        assertDoesNotThrow(() -> guard.validateDdl("CREATE INDEX idx_name ON t (name)"));
        assertDoesNotThrow(() -> guard.validateDdl("ALTER TABLE t ADD COLUMN c INT"));
        assertDoesNotThrow(() -> guard.validateDdl("DROP TABLE t"));
        assertDoesNotThrow(() -> guard.validateDdl("TRUNCATE TABLE t"));
    }

    @Test
    void rejectsSelectAndDmlInDdlTool() {
        assertThrows(IllegalArgumentException.class, () -> guard.validateDdl("SELECT * FROM t"));
        assertThrows(IllegalArgumentException.class, () -> guard.validateDdl("DELETE FROM t"));
        assertThrows(IllegalArgumentException.class, () -> guard.validateDdl("GRANT ALL ON t TO u"));
    }

    @Test
    void rejectsMultipleStatementsAndComments() {
        assertThrows(IllegalArgumentException.class,
                () -> guard.validateDml("DELETE FROM t WHERE id = 1; DELETE FROM t WHERE id = 2"));
        assertThrows(IllegalArgumentException.class, () -> guard.validateDml("DELETE FROM t -- comment"));
        assertThrows(IllegalArgumentException.class, () -> guard.validateDml("DELETE /* x */ FROM t"));
    }

    @Test
    void rejectsForbiddenOperations() {
        assertThrows(IllegalArgumentException.class,
                () -> guard.validateDml("SELECT * FROM t INTO OUTFILE '/tmp/x'"));
        assertThrows(IllegalArgumentException.class,
                () -> guard.validateDml("LOAD DATA INFILE '/tmp/x' INTO TABLE t"));
    }

    @Test
    void rejectsBlankAndMalformedSql() {
        assertThrows(IllegalArgumentException.class, () -> guard.validateDml("   "));
        assertThrows(IllegalArgumentException.class, () -> guard.validateDml("this is not sql"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO [dbo].[users] ([name]) VALUES (?)",
            "INSERT INTO users (id, name) VALUES (?, ?) ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name",
            "MERGE INTO users u USING source_users s ON (u.id = s.id) WHEN MATCHED THEN UPDATE SET u.name = s.name WHEN NOT MATCHED THEN INSERT (id, name) VALUES (s.id, s.name)",
            "MERGE INTO users AS u USING source_users AS s ON u.id = s.id WHEN MATCHED THEN UPDATE SET name = s.name;",
            "INSERT INTO default_catalog.demo.users (id, name) VALUES (?, ?)"
    })
    void acceptsDmlAcrossSupportedDatabases(String sql) {
        assertDoesNotThrow(() -> guard.validateDml(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO users (name) VALUES (?) RETURNING id",
            "INSERT INTO users (name) OUTPUT INSERTED.id VALUES (?)",
            "UPDATE users SET name = ? RETURNING id",
            "DELETE FROM users OUTPUT DELETED.id"
    })
    void rejectsResultReturningDmlBeforeItCanAutocommit(String sql) {
        assertThrows(IllegalArgumentException.class, () -> guard.validateDml(sql));
    }
}
