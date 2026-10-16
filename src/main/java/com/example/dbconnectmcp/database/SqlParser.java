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
            return CCJSqlParserUtil.parse(sql, parser -> parser.withSquareBracketQuotation(true));
        }
    }
}
