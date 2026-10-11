package com.agent.biz.tool;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL 安全守卫：只读 SQL 沙箱，防止删改数据 / 注入。
 *
 * 对应 docs/03_模块设计/03_03_biz_tools.md
 * 规则：
 *  - 仅允许 SELECT
 *  - 拦截多语句拼接（;）
 *  - 强制 LIMIT
 *  - 拦截危险关键字
 */
@Component
public class SqlGuard {

    private static final Pattern MULTI_STATEMENT = Pattern.compile(";\\s*[^\\s]");
    private static final Set<String> DANGEROUS_KEYWORDS = Set.of(
            "insert", "update", "delete", "drop", "alter", "truncate",
            "create", "grant", "revoke", "execute", "into", "set ");

    /**
     * 校验 SQL 是否安全可执行。
     *
     * @throws IllegalArgumentException SQL 不合法
     */
    public void validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 为空");
        }
        String lower = sql.toLowerCase().trim();
        // 1. 只允许 SELECT
        if (!lower.startsWith("select")) {
            throw new IllegalArgumentException("仅允许 SELECT 查询");
        }
        // 2. 拦截多语句
        if (MULTI_STATEMENT.matcher(sql).find()) {
            throw new IllegalArgumentException("禁止多语句拼接");
        }
        // 3. 拦截危险关键字
        for (String kw : DANGEROUS_KEYWORDS) {
            if (containsKeyword(lower, kw)) {
                throw new IllegalArgumentException("SQL 含危险关键字: " + kw);
            }
        }
        // 4. 强制 LIMIT
        if (!lower.matches(".*\\blimit\\s+\\d+\\s*$")) {
            throw new IllegalArgumentException("查询必须带 LIMIT");
        }
    }

    private boolean containsKeyword(String lower, String kw) {
        return lower.contains(kw) && !lower.replaceAll("\\bselect\\b", "").contains(kw);
    }
}
