package com.agent.biz.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SqlGuard 安全矩阵单元测试（连载第 13 章配套）。
 *
 * 覆盖三类用例：
 *  A. 合法 SELECT 必须放行（含曾经被误伤的真实业务 SQL）
 *  B. 写操作 / 注入 / DoS 必须拦截
 *  C. 规范化行为（注释剥离、空白压缩、词边界）
 */
class SqlGuardTest {

    private final SqlGuard guard = new SqlGuard();

    // ---------- A. 必须放行 ----------

    @Test
    void allowsSimpleSelect() {
        assertDoesNotThrow(() -> guard.validate("SELECT * FROM orders LIMIT 10"));
    }

    @Test
    void allowsLowerCaseAndNewlines() {
        assertDoesNotThrow(() -> guard.validate("select id, amount\nfrom orders\nwhere status = 'PAID'\nlimit 5"));
    }

    @Test
    void allowsLimitWithOffset() {
        assertDoesNotThrow(() -> guard.validate("SELECT id FROM orders LIMIT 20 OFFSET 40"));
    }

    @Test
    void allowsAggregateWithLimit() {
        assertDoesNotThrow(() -> guard.validate("SELECT status, count(*) AS cnt FROM orders GROUP BY status ORDER BY cnt DESC LIMIT 10"));
    }

    @Test
    void allowsColumnNameBackdrop_notMisidentifiedAsDrop() {
        // 修复验证：contains("drop") 时代会把 backdrop 误判为危险关键字
        assertDoesNotThrow(() -> guard.validate("SELECT backdrop_url FROM movies LIMIT 3"));
    }

    @Test
    void allowsColumnNameCreatedAt_notMisidentifiedAsCreate() {
        // 词边界：created_at 不应命中 \bcreate\b
        assertDoesNotThrow(() -> guard.validate("SELECT created_at, updated_at FROM orders LIMIT 3"));
    }

    @Test
    void allowsSemicolonInsideLineComment() {
        // 行注释里的分号在剥离后不应触发多语句拦截
        assertDoesNotThrow(() -> guard.validate("SELECT id FROM orders -- 注释; 故意的\nLIMIT 1"));
    }

    // ---------- B. 必须拦截 ----------

    @Test
    void rejectsNullAndBlank() {
        assertThrows(IllegalArgumentException.class, () -> guard.validate(null));
        assertThrows(IllegalArgumentException.class, () -> guard.validate("   "));
    }

    @Test
    void rejectsOverlongSql() {
        String big = "SELECT '" + "x".repeat(5000) + "' LIMIT 1";
        assertThrows(IllegalArgumentException.class, () -> guard.validate(big));
    }

    @Test
    void rejectsWriteOperations() {
        // 非SELECT开头的写语句首先被「仅允许SELECT」规则拦截，行为上等价于拒绝
        for (String sql : new String[]{
                "DELETE FROM orders WHERE id = 1 LIMIT 1",
                "UPDATE orders SET amount = 0 WHERE id = 1 LIMIT 1",
                "INSERT INTO orders VALUES (1) LIMIT 1",
                "DROP TABLE orders LIMIT 1",
                "TRUNCATE TABLE orders LIMIT 1",
                "ALTER TABLE orders ADD COLUMN x INT LIMIT 1"}) {
            assertThrows(IllegalArgumentException.class, () -> guard.validate(sql), sql);
        }
    }

    @Test
    void rejectsWriteKeywordSmuggledInsideSelect() {
        // SELECT 内部夹带写语句（多语句），应报「禁止多语句」
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> guard.validate("SELECT * FROM orders; DELETE FROM orders LIMIT 1"));
        assertTrue(ex.getMessage().contains("禁止多语句"), "多语句优先拦截");
        // SELECT 内部夹带 FOR UPDATE（锁表读），词边界命中应报「危险关键字」
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class,
                () -> guard.validate("SELECT * FROM orders FOR UPDATE LIMIT 1"));
        assertTrue(ex2.getMessage().contains("危险关键字"), "for update 应报危险关键字");
    }

    @Test
    void rejectsMultiStatementInjection() {
        assertThrows(IllegalArgumentException.class,
                () -> guard.validate("SELECT 1 LIMIT 1; DROP TABLE orders"));
    }

    @Test
    void rejectsBlockCommentSmuggling() {
        // 修复验证：块注释拆开关键字的时代可以绕过检查
        assertThrows(IllegalArgumentException.class,
                () -> guard.validate("SELECT/**/1/**/LIMIT/**/1/**/;/**/DROP/**/TABLE/**/orders"));
    }

    @Test
    void rejectsLineCommentSmuggling() {
        // 行注释藏到 LIMIT 之前：剥离注释后 LIMIT 应仍在结尾才放行
        assertThrows(IllegalArgumentException.class,
                () -> guard.validate("SELECT id FROM orders WHERE 1=1 AND (SELECT 1)=1 --\n; DELETE FROM orders"));
    }

    @Test
    void rejectsSelectInto() {
        assertThrows(IllegalArgumentException.class,
                () -> guard.validate("SELECT * INTO orders_bak FROM orders LIMIT 1"));
    }

    @Test
    void rejectsForUpdate() {
        assertThrows(IllegalArgumentException.class,
                () -> guard.validate("SELECT * FROM orders WHERE id = 1 FOR UPDATE LIMIT 1"));
    }

    @Test
    void rejectsDoSFunctions() {
        for (String sql : new String[]{
                "SELECT pg_sleep(30) LIMIT 1",
                "SELECT SLEEP(30) LIMIT 1"}) {
            assertThrows(IllegalArgumentException.class, () -> guard.validate(sql), sql);
        }
    }

    @Test
    void rejectsMissingLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> guard.validate("SELECT * FROM orders"));
    }

    @Test
    void rejectsLimitNotAtTail() {
        // LIMIT 在子查询里、主查询没 LIMIT：必须拒
        assertThrows(IllegalArgumentException.class,
                () -> guard.validate("SELECT * FROM (SELECT id FROM orders LIMIT 5) t"));
    }

    // ---------- C. 规范化 ----------

    @Test
    void normalizeStripsCommentsAndCollapsesWhitespace() {
        assertEquals("select 1 limit 1", SqlGuard.normalize("SELECT  -- hello\n  /* block */  1  LIMIT 1"));
    }

    @Test
    void commentTrickStillEndsWithLimit() {
        // 行注释剥离后 LIMIT 留在结尾：合法，应放行
        assertDoesNotThrow(() -> guard.validate("SELECT id FROM orders -- 取最近一单\nLIMIT 1"));
    }
}
