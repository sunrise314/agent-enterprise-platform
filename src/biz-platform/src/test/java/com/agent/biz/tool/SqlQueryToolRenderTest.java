package com.agent.biz.tool;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SqlQueryTool.render() 单元测试：用 H2 内存库构造真实 ResultSet 验证 markdown 渲染。
 * 注意：ResultSet 必须在 Connection/Statement 关闭前消费完毕（H2 语义），因此
 * 渲染断言全部放在 try-with-resources 作用域内完成。
 */
class SqlQueryToolRenderTest {

    @Test
    void rendersMarkdownTableWithNullAsNull() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:render_t1;DB_CLOSE_DELAY=-1");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE demo (id INT, name VARCHAR(50), amount DECIMAL(10,2))");
            st.execute("INSERT INTO demo VALUES (1, '订单A', 199.50), (2, NULL, NULL)");
            try (ResultSet rs = st.executeQuery("SELECT id, name, amount FROM demo ORDER BY id")) {
                SqlQueryTool.QueryResult r = SqlQueryTool.render(rs, 100);
                assertFalse(r.truncated());
                String md = r.markdown();
                assertTrue(md.startsWith("| ID | NAME | AMOUNT |"), md);
                assertTrue(md.contains("| --- | --- | --- |"));
                assertTrue(md.contains("订单A"), md);
                assertTrue(md.contains("| 2 | NULL | NULL |"), md);
            }
        }
    }

    @Test
    void truncatesBeyondMaxRows() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:render_t2;DB_CLOSE_DELAY=-1");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE big (id INT)");
            st.execute("INSERT INTO big SELECT x FROM SYSTEM_RANGE(1, 50)");
            try (ResultSet rs = st.executeQuery("SELECT id FROM big")) {
                SqlQueryTool.QueryResult r = SqlQueryTool.render(rs, 10);
                assertTrue(r.truncated());
                // 表头 2 行 + 10 行数据
                assertEquals(10, r.markdown().split("\n").length - 2);
            }
        }
    }

    @Test
    void emptyResultYieldsPlaceholder() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:render_t3;DB_CLOSE_DELAY=-1");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE demo2 (id INT, name VARCHAR(50))");
            try (ResultSet rs = st.executeQuery("SELECT id, name FROM demo2 WHERE id = 999")) {
                SqlQueryTool.QueryResult r = SqlQueryTool.render(rs, 100);
                assertFalse(r.truncated());
                assertEquals("(查询结果为空)", r.markdown());
            }
        }
    }
}
