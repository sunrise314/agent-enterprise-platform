package com.agent.biz.eval;

import org.springframework.stereotype.Component;

/**
 * 评估判定器：基于关键词断言判断回答质量。
 *
 * 这是「评测集 + 自动化断言」的轻量实现，适合无标注数据的快速回归。
 * 生产可升级为：LLM-as-judge / 语义相似度 / 人工标注。
 */
@Component
public class EvalJudge {

    /** 判定结果。 */
    public record JudgeResult(boolean success, boolean hallucination, String failReason) {
    }

    /**
     * 判定单条回答。
     * 断言模式见 {@link EvalCase#assertion()}：
     *  - contains_all：success=含全部 expectedKeywords，forbidden 命中判幻觉
     *  - contains_any：success=expectedKeywords 任一命中（识别"知识库缺失/拒绝"）
     *  - not_contains：success=不含任一 forbiddenKeywords（不得编造/越权）
     */
    public JudgeResult judge(EvalCase c, String answer) {
        String a = answer == null ? "" : answer;

        boolean forbiddenHit = c.forbiddenKeywords() != null
                && c.forbiddenKeywords().stream().anyMatch(a::contains);

        boolean success;
        switch (c.assertion()) {
            case "contains_any" -> success = c.expectedKeywords() != null
                    && c.expectedKeywords().stream().anyMatch(a::contains);
            case "not_contains" -> success = !forbiddenHit;
            default -> success = c.expectedKeywords() != null
                    && !c.expectedKeywords().isEmpty()
                    && c.expectedKeywords().stream().allMatch(a::contains);
        }

        String failReason = null;
        if (!success) {
            failReason = forbiddenHit ? "contains forbidden keywords" : "assertion not met";
        }
        return new JudgeResult(success, forbiddenHit, failReason);
    }
}
