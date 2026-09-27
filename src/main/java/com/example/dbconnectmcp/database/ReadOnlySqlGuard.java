package com.example.dbconnectmcp.database;

import java.util.regex.Pattern;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Component;

@Component
public class ReadOnlySqlGuard {

    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?is)\\b(?:for\\s+(?:update|share)|lock\\s+in\\s+share\\s+mode|into\\s+(?:out|dump)file|into\\s+@|"
                    + "get_lock\\s*\\(|release_lock\\s*\\(|sleep\\s*\\(|benchmark\\s*\\(|load_file\\s*\\()|:="
    );

    public void validate(String sql) {
        if (sql == null || sql.isBlank() || sql.length() > 20_000) {
            throw new IllegalArgumentException("SQL 不能为空且长度不能超过 20000 个字符。");
        }
        if (sql.indexOf(';') >= 0 || sql.contains("--") || sql.contains("/*") || sql.contains("#")) {
            throw new IllegalArgumentException("只允许单条无注释的只读查询。");
        }
        if (FORBIDDEN.matcher(sql).find()) {
            throw new IllegalArgumentException("查询包含不允许的操作。");
        }
        try {
            if (!(CCJSqlParserUtil.parse(sql) instanceof Select)) {
                throw new IllegalArgumentException("只允许 SELECT 查询，包括以 WITH 开头的 SELECT。");
            }
        }
        catch (JSQLParserException ex) {
            throw new IllegalArgumentException("无法解析查询 SQL，已拒绝执行。");
        }
    }
}
