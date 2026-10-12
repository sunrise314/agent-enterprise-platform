package com.agent.biz.tool;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * SQL 安全守卫：只读 SQL 沙箱，防止删改数据 / 注入 / 拖库。
 *
 * 对应 docs/03_模块设计/03_03_biz_tools.md、docs/连载/13。
 * 校验顺序（缺一不可）：
 *  1. 非空、长度上限（防超长 DoS）
 *  2. 剥离注释（-- 与 #{...} 块注释）——防「注释夹带关键字」绕过
 *  3. 压缩空白——防空白变形绕过
 *  4. 仅允许 SELECT 开头
 *  5. 禁止多语句（出现分号即拒，宁严勿漏）
 *  6. 危险关键字词边界匹配（防 backdrop 误伤 created_at 这类字段名）
 *  7. 防 DoS 函数（sleep / pg_sleep / benchmark）
 *  8. 强制 LIMIT 结尾
 *
 * 已知边界：正则沙箱仍是简化方案，生产终极方案是 Apache Calcite 语法解析
 * + 数据库账号只读授权双保险（见连载第 12 章 P0 清单）。
 */
@Component
public class SqlGuard {

    private static final int MAX_SQL_LENGTH = 4000;

    /** 行注释 -- ... 与块注释 #{...}（H2/PG 通用形态）。 */
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n\\r]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final Pattern SELECT_PREFIX = Pattern.compile("^select\\b.*");
    private static final Pattern MULTI_STATEMENT = Pattern.compile(";");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|truncate|create|grant|revoke"
                    + "|execute|exec|merge|replace|call|use|set|into|vacuum|copy)\\b"
                    + "|\\bfor\\s+update\\b");
    private static final Pattern DOS_FUNCS = Pattern.compile(
            "\\b(sleep|pg_sleep|benchmark|waitfor)\\s*\\(");
    private static final Pattern LIMIT_TAIL = Pattern.compile(
            ".*\\blimit\\s+\\d+(\\s+offset\\s+\\d+)?\\s*$");

    /**
     * 校验 SQL 是否安全可执行。
     *
     * @throws IllegalArgumentException SQL 不合法（message 面向 Agent，可直接展示）
     */
    public void validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 为空");
        }
        if (sql.length() > MAX_SQL_LENGTH) {
            throw new IllegalArgumentException("SQL 超长（>" + MAX_SQL_LENGTH + " 字符）");
        }
        // 1. 剥离注释 + 压缩空白（统一小写，后续全部基于规范化文本）
        String norm = normalize(sql);
        // 2. 仅允许 SELECT 开头
        if (!SELECT_PREFIX.matcher(norm).matches()) {
            throw new IllegalArgumentException("仅允许 SELECT 查询");
        }
        // 3. 禁止多语句（规范化后出现分号即拒：字面量含分号场景宁可误拦）
        if (MULTI_STATEMENT.matcher(norm).find()) {
            throw new IllegalArgumentException("禁止多语句拼接");
        }
        // 4. 危险关键字（词边界，backdrop / created_at 不误伤）
        java.util.regex.Matcher m = FORBIDDEN.matcher(norm);
        if (m.find()) {
            throw new IllegalArgumentException("SQL 含危险关键字: " + m.group());
        }
        // 5. 防 DoS 函数
        if (DOS_FUNCS.matcher(norm).find()) {
            throw new IllegalArgumentException("SQL 含禁止的函数调用");
        }
        // 6. 强制 LIMIT（允许 OFFSET）
        if (!LIMIT_TAIL.matcher(norm).matches()) {
            throw new IllegalArgumentException("查询必须以 LIMIT <n> 结尾（可带 OFFSET）");
        }
    }

    /** 注释剥离 + 空白压缩 + 小写。包级可见，供单元测试直接验证规范化行为。 */
    static String normalize(String sql) {
        String s = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        s = LINE_COMMENT.matcher(s).replaceAll(" ");
        s = WHITESPACE.matcher(s).replaceAll(" ").trim();
        return s.toLowerCase();
    }
}
