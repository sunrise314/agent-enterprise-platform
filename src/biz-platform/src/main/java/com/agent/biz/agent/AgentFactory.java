package com.agent.biz.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.middleware.AgentTraceMiddleware;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Agent 工厂：基于 AgentScope-Java 真实 API 构建 ReActAgent 实例。
 *
 * 对应 docs/03_模块设计/03_01_agent_core.md
 * 依据官方 ToolCallingExample / BasicChatExample 的真实用法编写。
 */
@Component
public class AgentFactory {

    @Value("${agent.llm.base-url:}")
    private String llmBaseUrl;

    @Value("${agent.llm.model:deepseek:deepseek-chat}")
    private String model;

    private final AgentGuard guard;
    private final AgentEventPublisher eventPublisher;

    public AgentFactory(AgentGuard guard, AgentEventPublisher eventPublisher) {
        this.guard = guard;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 构建一个生产 Agent。
     *
     * @param agentName   Agent 名称
     * @param sysPrompt   系统提示词
     * @param toolkit     已注册业务工具的 Toolkit（可为空）
     * @return ReActAgent 实例
     */
    public ReActAgent build(String agentName, String sysPrompt, Toolkit toolkit) {
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(agentName)
                .sysPrompt(sysPrompt)
                .model(model)
                // 追加事件监听：Agent 生命周期事件旁路上报给评估/监控
                .middleware(new AgentTraceMiddleware());

        if (toolkit != null) {
            builder.toolkit(toolkit);
        }
        return builder.build();
    }

    public AgentGuard getGuard() {
        return guard;
    }

    public AgentEventPublisher getEventPublisher() {
        return eventPublisher;
    }
}
