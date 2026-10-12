package com.agent.biz.tool;

import com.agent.biz.auth.TenantInfo;
import com.agent.biz.audit.AuditLogger;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.StringJoiner;

/**
 * SQL 沙箱查询工具：Agent 可对业务库执行只读 SELECT，但写不进任何数据。
 *
 * 对应 docs/03_模块设计/03_03_biz_tools.md、docs/连载/13。
 * 四道防线（纵深防御，缺一层都不可交付）：
 *  1. {@link SqlGuard} 静态校验（进入执行前的所有拦截）
 *  2. JDBC 层 queryTimeout —— 单条 SQL 最多跑 {@code sql.tool.timeout-seconds} 秒
 *  3. JDBC 层 maxRows —— 最多返回 {@code sql.tool.max-rows} 行，防全表拖库
 *  4. 审计留痕 —— 每次执行记 tool.sql_query（含 SQL 原文、成败、耗时）
 *
 * 最后一道兜底：生产环境数据库账号本身只授予 SELECT 权限，
 * 即使前三层全部被绕过，写入语句在数据库侧也会被拒绝。
 */
@Component
public class SqlQueryTool {

    private final DataSource dataSource;
    private final SqlGuard guard;
    private final AuditLogger audit;
    private final int timeoutSeconds;
    private final int maxRows;

    public SqlQueryTool(DataSource dataSource,
                        SqlGuard guard,
                        AuditLogger audit,
                        @Value("${sql.tool.timeout-seconds:5}") int timeoutSeconds,
                        @Value("${sql.tool.max-rows:100}") int maxRows) {
        this.dataSource = dataSource;
        this.guard = guard;
        this.audit = audit;
        this.timeoutSeconds = timeoutSeconds;
        this.maxRows = maxRows;
    }

    /**
     * 只读 SQL 查询工具（注册给 Agent）。
     */
    @Tool(
            name = "sql_query",
            description = "对业务数据库执行只读 SELECT 查询并返回表格结果。"
                    + "仅支持单条 SELECT 语句，必须以 LIMIT <n> 结尾（最多 "
                    + "100 行）。适用于统计、核对、列表类问题；制度类问题请改用 kb_search。",
            readOnly = true)
    public String sqlQuery(
            @ToolParam(name = "sql", description = "完整的 SELECT 语句，必须带 LIMIT") String sql,
            TenantInfo tenant) {
        long start = System.currentTimeMillis();
        String tenantId = tenant == null ? "tenant-0" : tenant.tenantId();
        Long userId = tenant == null ? null : tenant.userId();
        try {
            guard.validate(sql);
        } catch (IllegalArgumentException e) {
            long cost = System.currentTimeMillis() - start;
            audit.record(tenantId, userId, "tool.sql_query", "business-db",
                    "rejected: " + e.getMessage(), false, cost);
            return "查询被安全策略拒绝: " + e.getMessage();
        }

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.setQueryTimeout(timeoutSeconds);
            st.setMaxRows(maxRows + 1); // 多取 1 行用于判断截断
            long qStart = System.currentTimeMillis();
            try (ResultSet rs = st.executeQuery(sql)) {
                QueryResult qr = render(rs, maxRows);
                long cost = System.currentTimeMillis() - start;
                audit.record(tenantId, userId, "tool.sql_query", "business-db",
                        sql, true, cost);
                return qr.markdown + "\n(查询耗时 "
                        + (System.currentTimeMillis() - qStart) + " ms"
                        + (qr.truncated ? "，结果超过 " + maxRows + " 行已截断，请缩小查询范围" : "")
                        + ")";
            }
        } catch (SQLException e) {
            long cost = System.currentTimeMillis() - start;
            audit.record(tenantId, userId, "tool.sql_query", "business-db", sql, false, cost);
            // 不向 Agent 暴露堆栈与连接串细节
            return "查询执行失败: " + e.getClass().getSimpleName() + " " + e.getMessage();
        }
    }

    /** 结果集转 markdown 表格；静态方法便于单元测试。 */
    static QueryResult render(ResultSet rs, int maxRows) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        StringJoiner head = new StringJoiner(" | ", "| ", " |");
        StringJoiner sep = new StringJoiner(" | ", "| ", " |");
        for (int i = 1; i <= cols; i++) {
            head.add(md.getColumnLabel(i));
            sep.add("---");
        }
        StringBuilder sb = new StringBuilder(head.toString()).append('\n').append(sep.toString());
        int rows = 0;
        boolean truncated = false;
        while (rs.next()) {
            if (rows >= maxRows) {
                truncated = true;
                break;
            }
            StringJoiner row = new StringJoiner(" | ", "| ", " |");
            for (int i = 1; i <= cols; i++) {
                Object v = rs.getObject(i);
                row.add(v == null ? "NULL" : String.valueOf(v));
            }
            sb.append('\n').append(row);
            rows++;
        }
        if (rows == 0) {
            return new QueryResult("(查询结果为空)", false);
        }
        return new QueryResult(sb.toString(), truncated);
    }

    /** 渲染结果：markdown 文本 + 是否截断。 */
    record QueryResult(String markdown, boolean truncated) {
    }
}
