package com.agent.biz.metrics;

import java.util.List;
import java.util.Map;

/**
 * 指标存储抽象：记录 LLM 调用 / Agent 调用 / 工具调用指标，并支持窗口聚合查询。
 *
 * 实现：
 *  - {@link InMemoryMetricsStore}：demo（按分钟聚合，环形缓冲，可经 /api/metrics 查询）
 *  - 生产：InfluxDB / PostgreSQL(时序) + Telegraf 采集，Grafana 可视化（见 docs/03_模块设计/03_06_observability.md）
 *
 * 对应 docs/03_模块设计/03_06_observability.md「指标采集」。
 */
public interface MetricsStore {

    /** 记录一次 LLM 调用指标。 */
    void recordLlmCall(String tenantId, int tokenIn, int tokenOut, long costMs);

    /** 记录一次 Agent 调用（成功/失败）。 */
    void recordAgentCall(String tenantId, String sessionKey, boolean success, long costMs);

    /** 记录一次工具调用。 */
    void recordToolCall(String tenantId, String toolName, boolean success, long costMs);

    /** 汇总指标（最近窗口）。 */
    Summary summary(long windowMs);

    /** 时序点（按分钟聚合，供折线图）。 */
    List<DataPoint> series();

    /** 汇总指标。 */
    record Summary(long calls, long llmCalls, long tokenIn, long tokenOut,
                   long failedCalls, double p95LatencyMs, double avgLatencyMs) {
    }

    /** 时序点。 */
    record DataPoint(long epochMinute, long calls, long tokenTotal, double avgLatencyMs) {
    }
}
