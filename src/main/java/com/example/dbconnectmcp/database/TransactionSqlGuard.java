package com.example.dbconnectmcp.database;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.upsert.Upsert;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * Validates complete transaction scripts before execution. Locking reads and returning
 * rows are allowed here, unlike the independent read-only and update-count tools.
 * TRUNCATE is the sole DDL exception; dialect-specific implicit commits are reported.
 */
public final class TransactionSqlGuard {
    enum Command { BEGIN, COMMIT, ROLLBACK, EXECUTE }
    record Sql(String text, boolean truncate, boolean merge, boolean query) { }
    record Plan(Command command, List<Sql> statements) { }
    private final DatabaseTransactionProperties limits;
    private static final Pattern BEGIN = Pattern.compile("(?is)(?:BEGIN(?:\\s+(?:TRANSACTION|WORK))?|START\\s+TRANSACTION)");
    private static final Pattern END = Pattern.compile("(?is)(COMMIT|ROLLBACK)(?:\\s+(?:TRANSACTION|WORK))?");
    // These operations can escape the managed connection/transaction or invoke external SQL.
    private static final Pattern UNSAFE = Pattern.compile("(?is)\\b(?:into\\s+(?:out|dump)file|load\\s+data|"
            + "dblink\\w*\\s*\\(|lo_import\\s*\\(|lo_export\\s*\\(|set_config\\s*\\(|"
            + "openquery\\s*\\(|openrowset\\s*\\()|:=");

    public TransactionSqlGuard(DatabaseTransactionProperties limits) { this.limits = limits; }

    Plan validate(String sql) {
        if (sql == null || sql.isBlank() || sql.length() > limits.maxSqlChars()) {
            throw new IllegalArgumentException("INVALID_SQL: SQL is empty or exceeds the configured script limit.");
        }
        List<String> parts = split(sql);
        if (parts.isEmpty() || parts.size() > limits.maxStatements()) {
            throw new IllegalArgumentException("INVALID_SQL: Invalid number of statements.");
        }
        if (parts.size() == 1) {
            if (BEGIN.matcher(parts.getFirst()).matches()) { return new Plan(Command.BEGIN, List.of()); }
            var end = END.matcher(parts.getFirst());
            if (end.matches()) { return new Plan(Command.valueOf(end.group(1).toUpperCase(Locale.ROOT)), List.of()); }
        }
        try {
            // Parse the whole script before any JDBC call, including statements after DML.
            var parsed = SqlParser.parseStatements(sql);
            if (parsed.size() != parts.size()) { throw rejected(); }
            List<Sql> statements = new ArrayList<>();
            for (int i = 0; i < parsed.size(); i++) {
                Statement statement = parsed.get(i);
                if (!(statement instanceof Select || statement instanceof Insert || statement instanceof Update
                        || statement instanceof Delete || statement instanceof Merge || statement instanceof Upsert
                        || statement instanceof Truncate)) { throw rejected(); }
                if (UNSAFE.matcher(parts.get(i)).find()) { throw rejected(); }
                // Inspect nested SELECTs as well: SELECT INTO can hide in CTEs/subqueries.
                new TablesNamesFinder<Void>() {
                    @Override
                    public <S> Void visit(JdbcParameter parameter, S context) { throw rejected(); }
                    @Override
                    public <S> Void visit(JdbcNamedParameter parameter, S context) { throw rejected(); }
                    @Override
                    public <S> Void visit(PlainSelect select, S context) {
                        if ((select.getIntoTables() != null && !select.getIntoTables().isEmpty())
                                || select.getIntoTempTable() != null || select.getMySqlSelectIntoClause() != null) {
                            throw rejected();
                        }
                        return super.visit(select, context);
                    }
                    @Override
                    public <S> Void visit(WithItem<?> item, S context) {
                        if (item.getWithFunctionDeclaration() != null) { throw rejected(); }
                        return super.visit(item, context);
                    }
                }.getTables(statement);
                // Preserve the caller's text without a JDBC terminator (Oracle rejects it).
                statements.add(new Sql(parts.get(i), statement instanceof Truncate,
                        statement instanceof Merge, statement instanceof Select));
            }
            return new Plan(Command.EXECUTE, List.copyOf(statements));
        }
        catch (JSQLParserException | UnsupportedOperationException error) { throw rejected(); }
    }

    private static IllegalArgumentException rejected() {
        return new IllegalArgumentException("SQL_REJECTED: Only queries, DML, and TRUNCATE are allowed. "
                + "Send BEGIN, COMMIT, or ROLLBACK separately; other DDL and indirect SQL execution are forbidden.");
    }

    /**
     * Preserve statement text while ignoring semicolons inside quoted values/identifiers.
     * The parser independently checks the complete script and statement count afterward.
     * Reject comments outside literals, including MySQL executable/versioned comments.
     */
    private List<String> split(String sql) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (sql.startsWith("--", i) || sql.startsWith("/*", i) || ch == '#') { throw rejected(); }
            if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') {
                char close = ch == '[' ? ']' : ch;
                boolean ended = false;
                while (++i < sql.length()) {
                    if (sql.charAt(i) == close) {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == close) { i++; }
                        else { ended = true; break; }
                    }
                    // Backslash quoting varies by SQL mode; the AST count check fails
                    // closed if the database-independent splitter cannot match the parser.
                    else if (sql.charAt(i) == '\\' && ch == '\'' && i + 1 < sql.length()) { i++; }
                }
                if (!ended) { throw rejected(); }
            }
            else if (ch == '$') {
                var tag = Pattern.compile("\\$(?:[A-Za-z_][A-Za-z_0-9]*)?\\$").matcher(sql.substring(i));
                if (tag.lookingAt()) {
                    String delimiter = tag.group();
                    int end = sql.indexOf(delimiter, i + delimiter.length());
                    if (end < 0) { throw rejected(); }
                    i = end + delimiter.length() - 1;
                }
            }
            else if (ch == ';') {
                String part = sql.substring(start, i).strip();
                if (part.isEmpty()) { throw rejected(); }
                parts.add(part);
                start = i + 1;
            }
        }
        String last = sql.substring(start).strip();
        if (!last.isEmpty()) { parts.add(last); }
        return parts;
    }
}
