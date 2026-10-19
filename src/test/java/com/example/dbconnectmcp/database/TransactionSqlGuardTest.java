package com.example.dbconnectmcp.database;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TransactionSqlGuardTest {
    private final TransactionSqlGuard guard = new TransactionSqlGuard(new DatabaseTransactionProperties(120, 600, 5, 20, 20000, 30));

    @Test
    void recognizesStandaloneControlsAndPreservesLiteralSemicolons() {
        assertEquals(TransactionSqlGuard.Command.BEGIN, guard.validate("START TRANSACTION;").command());
        assertEquals(TransactionSqlGuard.Command.BEGIN, guard.validate("begin work").command());
        assertEquals(TransactionSqlGuard.Command.COMMIT, guard.validate("COMMIT WORK;").command());
        assertEquals(TransactionSqlGuard.Command.ROLLBACK, guard.validate("ROLLBACK TRANSACTION").command());
        var script = guard.validate("UPDATE t SET value = 'a;--b'; SELECT 'it''s;ok' FROM t;");
        assertEquals(2, script.statements().size());
        assertEquals("UPDATE t SET value = 'a;--b'", script.statements().getFirst().text());
        assertEquals(1, guard.validate("SELECT $$a;b$$").statements().size());
        assertEquals(1, guard.validate("SELECT arr[1] FROM t").statements().size());
        assertEquals(1, guard.validate("SELECT [odd;name] FROM [t]").statements().size());
    }

    @Test
    void allowsLockingReadsReturningCtesAndTruncate() {
        for (String sql : new String[] {
                "SELECT * FROM t FOR UPDATE", "SELECT * FROM t WITH (UPDLOCK)",
                "WITH x AS (SELECT id FROM t) SELECT * FROM x",
                "WITH x AS (DELETE FROM t RETURNING id) SELECT * FROM x",
                "UPDATE t SET value = 'x' RETURNING id", "DELETE t OUTPUT deleted.id FROM t",
                "UPDATE t SET value='x' OUTPUT inserted.id", "INSERT INTO t(id) OUTPUT inserted.id VALUES(1)",
                "INSERT INTO t(id) VALUES(1)", "TRUNCATE TABLE t", "TRUNCATE TABLE t CASCADE"}) {
            assertDoesNotThrow(() -> guard.validate(sql), sql);
        }
        assertTrue(guard.validate("TRUNCATE TABLE t").statements().getFirst().truncate());
    }

    @Test
    void rejectsDdlIndirectExecutionAndMixedControlsBeforeExecution() {
        for (String sql : new String[] {
                "UPDATE t SET id = 1; DROP TABLE t", "SELECT 1; CREATE TABLE x(id INT)",
                "ALTER TABLE t ADD x INT", "RENAME TABLE t TO x", "GRANT SELECT ON t TO u",
                "SELECT * INTO new_table FROM t", "WITH x AS (SELECT * INTO new_table FROM t) SELECT * FROM x",
                "CALL do_work()", "EXEC do_work", "DO $$BEGIN EXECUTE 'DROP TABLE t'; END$$",
                "SET AUTOCOMMIT = 1", "USE other_db", "BEGIN; UPDATE t SET id = 1", "SELECT 1; COMMIT",
                "SAVEPOINT x", "ROLLBACK TO SAVEPOINT x", "COMMIT AND CHAIN",
                "SELECT 1 /* comment */", "SELECT 1; /*! DROP TABLE t */", "SELECT 1;;",
                "SELECT dblink_exec('conn', 'DROP TABLE t')", "SELECT * FROM t WHERE id=?", "UPDATE t SET id=:id"}) {
            assertThrows(IllegalArgumentException.class, () -> guard.validate(sql), sql);
        }
    }
}
