package com.agent.biz.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Agent 安全守卫：防止 Agent 死循环、超时、无上限重试。
 *
 * 对应文档：docs/03_模块设计/03_01_agent_core.md
 */
@Component
public class AgentGuard {

    private final int maxToolRounds;
    private final int timeoutSeconds;
    private final int maxRetries;

    public AgentGuard(
            @Value("${agent.max-tool-rounds:8}") int maxToolRounds,
            @Value("${agent.timeout-seconds:60}") int timeoutSeconds,
            @Value("${agent.llm.max-retries:3}") int maxRetries) {
        this.maxToolRounds = maxToolRounds;
        this.timeoutSeconds = timeoutSeconds;
        this.maxRetries = maxRetries;
    }

    /**
     * 校验工具调用是否已达最大轮次，防止死循环。
     */
    public boolean exceedMaxRounds(int currentRound) {
        return currentRound >= maxToolRounds;
    }

    /**
     * 校验单次执行是否超时。
     */
    public boolean isTimeout(long startNanos) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        return elapsedMs > timeoutSeconds * 1000L;
    }

    public int getMaxToolRounds() {
        return maxToolRounds;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }
}
