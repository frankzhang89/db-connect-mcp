package com.example.dbconnectmcp.database;

import java.util.regex.Pattern;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.RenameTableStatement;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.view.CreateView;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.upsert.Upsert;
import org.springframework.stereotype.Component;

/**
 * Whitelist-based validation for single DML/DDL statements. SELECT stays in
 * {@link ReadOnlySqlGuard}; anything outside the whitelisted statement types is
 * rejected before reaching the database.
 */
@Component
public class WriteSqlGuard {

    // File exports, LOAD DATA, server-side prepared statements, and handlers
    // are rejected outright even before the whitelist check runs.
    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?is)\\b(?:into\\s+(?:out|dump)file|load\\s+data|prepare\\s+|handler\\s+)|:=");

    public void validateDml(String sql) {
        Statement statement = parseSingleStatement(sql);
        // Only row-modifying statements are accepted for the DML tool.
        if (!(statement instanceof Insert || statement instanceof Update || statement instanceof Delete
                || statement instanceof Merge || statement instanceof Upsert)) {
            throw new IllegalArgumentException("The DML tool only accepts INSERT, UPDATE, DELETE, MERGE, or UPSERT statements.");
        }
    }

    public void validateDdl(String sql) {
        Statement statement = parseSingleStatement(sql);
        // Only schema-changing statements are accepted for the DDL tool.
        if (!(statement instanceof CreateTable || statement instanceof CreateView || statement instanceof CreateIndex
                || statement instanceof Alter || statement instanceof Drop || statement instanceof Truncate
                || statement instanceof RenameTableStatement)) {
            throw new IllegalArgumentException("The DDL tool only accepts CREATE, ALTER, DROP, TRUNCATE, or RENAME statements.");
        }
    }

    private Statement parseSingleStatement(String sql) {
        // Reject empty or oversized input.
        if (sql == null || sql.isBlank() || sql.length() > 20_000) {
            throw new IllegalArgumentException("SQL must not be empty and must not exceed 20000 characters.");
        }
        // Single statement only: no semicolons and no comments of any style.
        if (sql.indexOf(';') >= 0 || sql.contains("--") || sql.contains("/*") || sql.contains("#")) {
            throw new IllegalArgumentException("Only a single comment-free statement is allowed.");
        }
        if (FORBIDDEN.matcher(sql).find()) {
            throw new IllegalArgumentException("The statement contains disallowed operations.");
        }
        try {
            return CCJSqlParserUtil.parse(sql);
        }
        catch (JSQLParserException ex) {
            throw new IllegalArgumentException("The SQL could not be parsed and was rejected.");
        }
    }
}
