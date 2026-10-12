package com.agent.biz.tool;

import com.agent.biz.auth.TenantInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SqlQueryTool 集成测试（@SpringBootTest，H2 demo 数据源）：
 * 验证 Spring 装配路径 —— DataSource 注入、SqlGuard 拦截、真实 JDBC 执行、markdown 输出。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:sqltool_it;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
class SqlQueryToolIntegrationTest {

    @Autowired
    private SqlQueryTool tool;

    @Autowired
    private javax.sql.DataSource dataSource;

    @Test
    void endToEndQueryThroughSpringWiring() throws Exception {
        // 在同一数据源上准备一张业务表
        try (var conn = dataSource.getConnection();
             var st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS expense (id INT, item VARCHAR(50), amount DECIMAL(10,2))");
            st.execute("MERGE INTO expense KEY(id) VALUES (1, '差旅', 860.00), (2, '办公用品', 132.50)");
        }

        String out = tool.sqlQuery("SELECT id, item, amount FROM expense ORDER BY id LIMIT 10",
                TenantInfo.of("tenant-it", 42L));
        assertNotNull(out);
        assertTrue(out.contains("| ID | ITEM | AMOUNT |"), out);
        assertTrue(out.contains("860.00"), out);
        assertTrue(out.contains("查询耗时"), out);
    }

    @Test
    void guardRejectionIsAuditedAndReturnedAsMessage() {
        String out = tool.sqlQuery("DELETE FROM expense WHERE id = 1 LIMIT 1",
                TenantInfo.of("tenant-it", 42L));
        assertTrue(out.contains("查询被安全策略拒绝"), out);
    }

    @Test
    void missingLimitIsRejected() {
        String out = tool.sqlQuery("SELECT * FROM expense", TenantInfo.of("tenant-it", 42L));
        assertTrue(out.contains("LIMIT"), out);
    }
}
