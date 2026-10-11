package com.agent.biz.eval;

import org.springframework.stereotype.Component;

/**
 * 评估指标采集器（旁路，不侵入主链路）。
 *
 * 对应 docs/03_模块设计/03_04_evaluation.md（面试核心）
 * 指标分三层：
 *  - 任务层：任务完成率、平均轮次、失败根因分布
 *  - 质量层：事实准确率、幻觉率、指令遵从度、工具调用准确率
 *  - 成本性能层：token 消耗、端到端耗时、重试率
 */
@Component
public class MetricCollector {

    /** 单条 case 的指标聚合对象。 */
    public record CaseMetrics(
            boolean success,          // 任务是否成功
            boolean toolCallOk,       // 工具调用是否正确
            boolean hallucination,    // 是否幻觉
            int tokenIn,
            int tokenOut,
            long latencyMs,
            int retryCount,
            String failReason
    ) {
    }

    /**
     * 汇总一批 case 的评估指标。
     */
    public Summary summarize(java.util.List<CaseMetrics> metrics) {
        int total = metrics.size();
        if (total == 0) {
            return new Summary(0d, 0d, 0d, 0d, 0L, 0d);
        }
        long success = metrics.stream().filter(m -> m.success).count();
        long toolOk = metrics.stream().filter(m -> m.toolCallOk).count();
        long halluc = metrics.stream().filter(m -> m.hallucination).count();
        double avgToken = metrics.stream()
                .mapToInt(m -> m.tokenIn + m.tokenOut).average().orElse(0);
        long p95 = p95(metrics.stream().mapToLong(m -> m.latencyMs).sorted().toArray());
        double retryRate = (double) metrics.stream().mapToInt(m -> m.retryCount).sum() / total;
        return new Summary(
                (double) success / total,      // 任务完成率
                (double) toolOk / total,       // 工具调用准确率
                (double) halluc / total,       // 幻觉率
                avgToken,                      // 平均 token
                p95,                           // P95 延迟
                retryRate);                    // 重试率
    }

    private long p95(long[] sorted) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(sorted.length * 0.95) - 1;
        return sorted[Math.max(0, idx)];
    }

    /** 汇总结果。 */
    public record Summary(
            double taskCompletionRate,
            double toolCallAccuracy,
            double hallucinationRate,
            double avgToken,
            long p95LatencyMs,
            double retryRate
    ) {
    }
}
