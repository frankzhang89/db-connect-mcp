package com.example.dbconnectmcp.database;

import java.util.regex.Pattern;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.Statement;

/** A deliberately small EXPLAIN grammar; never executes ANALYZE or arbitrary options. */
record ExplainSql(String query, boolean oracleSyntax) {
    private static final Pattern PREFIX = Pattern.compile(
            "(?is)^\\s*EXPLAIN\\s+(?:(PLAN\\s+FOR)\\s+)?(?=(?:SELECT|WITH)\\b)");

    static ExplainSql from(String sql, Statement statement) {
        if (!(statement instanceof ExplainStatement)) { return null; }
        var prefix = PREFIX.matcher(sql);
        if (!prefix.find()) {
            throw new IllegalArgumentException("Only EXPLAIN SELECT/WITH SELECT and EXPLAIN PLAN FOR SELECT/WITH SELECT are supported; ANALYZE and options are not allowed.");
        }
        String query = sql.substring(prefix.end());
        // Validate the original inner text, including nested CTEs, before any JDBC call.
        new ReadOnlySqlGuard().validate(query);
        return new ExplainSql(query, prefix.group(1) != null);
    }

    void validateDialect(DatabaseDialect dialect, boolean transactional) {
        if (oracleSyntax && !(dialect instanceof OracleDialect)) {
            throw new IllegalArgumentException("EXPLAIN PLAN FOR is only supported on Oracle through db_execute.");
        }
        if (!transactional && (dialect instanceof OracleDialect || dialect instanceof SqlServerDialect)) {
            throw new IllegalArgumentException("Oracle and SQL Server execution plans require db_execute.");
        }
    }
}
