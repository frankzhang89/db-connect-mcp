package com.example.dbconnectmcp.database;

import java.util.regex.Pattern;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Component;

/**
 * Validates that a SQL string is a single read-only SELECT before it reaches
 * the database. Defense in depth: string-level checks reject multi-statement
 * tricks and side-effect functions, and a JSqlParser parse confirms the
 * statement type.
 */
@Component
public class ReadOnlySqlGuard {

    // Locking clauses, file exports, user variables, and dangerous built-ins.
    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?is)\\b(?:for\\s+(?:update|share)|lock\\s+in\\s+share\\s+mode|into\\s+(?:out|dump)file|into\\s+@|"
                    + "get_lock\\s*\\(|release_lock\\s*\\(|sleep\\s*\\(|benchmark\\s*\\(|load_file\\s*\\()|:="
    );

    public void validate(String sql) {
        // Reject empty or oversized input.
        if (sql == null || sql.isBlank() || sql.length() > 20_000) {
            throw new IllegalArgumentException("SQL must not be empty and must not exceed 20000 characters.");
        }
        // Single statement only: no semicolons and no comments of any style.
        if (sql.indexOf(';') >= 0 || sql.contains("--") || sql.contains("/*") || sql.contains("#")) {
            throw new IllegalArgumentException("Only a single comment-free read-only query is allowed.");
        }
        if (FORBIDDEN.matcher(sql).find()) {
            throw new IllegalArgumentException("The query contains disallowed operations.");
        }
        // Parse and confirm the root statement is a SELECT (covers WITH ... SELECT).
        try {
            if (!(CCJSqlParserUtil.parse(sql) instanceof Select)) {
                throw new IllegalArgumentException("Only SELECT queries are allowed, including SELECT queries that start with WITH.");
            }
        }
        catch (JSQLParserException ex) {
            throw new IllegalArgumentException("The query SQL could not be parsed and was rejected.");
        }
    }
}
