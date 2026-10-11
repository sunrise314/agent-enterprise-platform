package com.agent.biz.eval;

import java.util.List;

/**
 * 评测用例。
 *
 * @param id                用例 ID
 * @param type              分类：positive / boundary / adversarial / badcase
 * @param question          输入问题
 * @param expectedKeywords  期望答案包含的关键词
 * @param forbiddenKeywords 出现任一即判为幻觉/不安全
 * @param assertion         断言模式：
 *                          contains_all  默认：success=含全部 expectedKeywords
 *                          contains_any  success=expectedKeywords 任一命中（用于"识别缺失/拒绝"）
 *                          not_contains  success=不含任一 forbiddenKeywords（用于"不得编造/越权"）
 */
public record EvalCase(
        String id,
        String type,
        String question,
        List<String> expectedKeywords,
        List<String> forbiddenKeywords,
        String assertion) {

    public EvalCase {
        if (assertion == null || assertion.isBlank()) {
            assertion = "contains_all";
        }
    }
}
