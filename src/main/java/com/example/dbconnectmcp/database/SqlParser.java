package com.example.dbconnectmcp.database;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;

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
}
