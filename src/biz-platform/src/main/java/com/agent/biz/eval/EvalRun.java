package com.agent.biz.eval;

import com.agent.biz.eval.MetricCollector.Summary;

import java.util.List;

/**
 * 一次评估批次的结果。
 */
public record EvalRun(
        long id,
        String name,
        String type,
        int total,
        int success,
        int fail,
        Summary summary,
        List<CaseResult> cases) {

    /** 单条用例的执行结果。 */
    public record CaseResult(
            String caseId,
            String type,
            String question,
            String answer,
            boolean success,
            boolean hallucination,
            String failReason,
            long latencyMs,
            int tokenTotal,
            Integer semanticScore,
            String judgeComment) {
    }
}
