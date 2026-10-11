package com.agent.biz.metrics;

import com.agent.biz.agent.AgentEventListener;
import com.agent.biz.agent.AgentEventPublisher;
import com.agent.biz.auth.TenantContext;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 指标订阅器：旁路订阅 Agent 生命周期事件，聚合写入 MetricsStore。
 *
 * 设计：与评估监听器同样走 AgentEventPublisher 旁路，不阻塞 Agent 主链路。
 * 指标维度：LLM 调用（token/耗时）、Agent 调用（成功/失败/耗时）、工具调用。
 */
@Component
public class MetricsSubscriber implements AgentEventListener {

    private final AgentEventPublisher publisher;
    private final MetricsStore store;

    public MetricsSubscriber(AgentEventPublisher publisher, MetricsStore store) {
        this.publisher = publisher;
        this.store = store;
    }

    @PostConstruct
    public void register() {
        publisher.register(this);
    }

    @Override
    public void onLlmCall(String traceId, int tokenIn, int tokenOut, long costMs) {
        store.recordLlmCall(tenant(), tokenIn, tokenOut, costMs);
    }

    @Override
    public void onAgentFinished(String traceId, String sessionKey, long costMs) {
        store.recordAgentCall(tenant(), sessionKey, true, costMs);
    }

    @Override
    public void onAgentFailed(String traceId, String sessionKey, String reason) {
        store.recordAgentCall(tenant(), sessionKey, false, 0);
    }

    @Override
    public void onToolResult(String traceId, String toolName, boolean success, long costMs, String resultSummary) {
        store.recordToolCall(tenant(), toolName, success, costMs);
    }

    private String tenant() {
        return TenantContext.tenantId();
    }
}
