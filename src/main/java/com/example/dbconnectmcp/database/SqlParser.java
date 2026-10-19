package com.example.dbconnectmcp.database;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;

/** Preserve PostgreSQL array syntax while also accepting T-SQL [identifiers]. */
final class SqlParser {

    private SqlParser() { }

    static Statement parse(String sql) throws JSQLParserException {
        try {
            return CCJSqlParserUtil.parse(sql);
        }
        catch (JSQLParserException ex) {
            // Retry bracket quoting only after ordinary parsing fails, because enabling
            // it first can reinterpret PostgreSQL array subscripts as quoted identifiers.
            return CCJSqlParserUtil.parse(sql, parser -> parser.withSquareBracketQuotation(true));
        }
    }

    /** Parses the entire script before the first statement is allowed to execute. */
    static Statements parseStatements(String sql) throws JSQLParserException {
        try {
            return CCJSqlParserUtil.parseStatements(sql);
        }
        catch (JSQLParserException ex) {
            try {
                return CCJSqlParserUtil.parseStatements(sql, parser -> parser.withSquareBracketQuotation(true));
            }
            catch (JSQLParserException bracketError) {
                // JSqlParser 5.4 recognizes NOLOCK but not common locking hints. Normalize
                // only known hint lists for AST inspection; execution retains the original SQL.
                String hint = "(?:UPDLOCK|XLOCK|HOLDLOCK|ROWLOCK|PAGLOCK|TABLOCK|TABLOCKX|READCOMMITTEDLOCK|READPAST|NOWAIT)";
                String normalized = sql.replaceAll("(?i)\\bWITH\\s*\\(\\s*" + hint + "(?:\\s*,\\s*" + hint + ")*\\s*\\)", "WITH (NOLOCK)");
                if (normalized.equals(sql)) { throw bracketError; }
                return CCJSqlParserUtil.parseStatements(normalized, parser -> parser.withSquareBracketQuotation(true));
            }
        }
    }
}
