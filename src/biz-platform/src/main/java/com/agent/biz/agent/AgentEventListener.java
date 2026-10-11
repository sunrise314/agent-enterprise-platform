package com.agent.biz.agent;

/**
 * Agent 生命周期事件监听接口。
 *
 * 所有 Agent 执行事件通过该接口旁路上报给评估模块与监控模块，
 * 不阻塞 Agent 主执行链路（对应 docs/03_04_evaluation.md 与 03_06_observability.md）。
 */
public interface AgentEventListener {

    default void onAgentStarted(String sessionKey, String traceId) {
    }

    default void onToolCalling(String traceId, String toolName, String argsJson) {
    }

    default void onToolResult(String traceId, String toolName, boolean success,
                              long costMs, String resultSummary) {
    }

    default void onLlmCall(String traceId, int tokenIn, int tokenOut, long costMs) {
    }

    default void onAgentFinished(String traceId, String sessionKey, long costMs) {
    }

    default void onAgentFailed(String traceId, String sessionKey, String reason) {
    }
}
