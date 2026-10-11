package com.agent.biz.agent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent 事件发布器：维护事件监听器集合，向评估 / 监控模块广播事件。
 *
 * 设计要点：
 * 1. 旁路异步，不阻塞主链路；
 * 2. 事件监听器可动态注册 / 移除。
 */
@Component
public class AgentEventPublisher {

    private final List<AgentEventListener> listeners = new CopyOnWriteArrayList<>();

    private final ConcurrentHashMap<String, Long> startTime = new ConcurrentHashMap<>();

    public void register(AgentEventListener listener) {
        listeners.add(listener);
    }

    public void agentStarted(String sessionKey, String traceId) {
        startTime.put(traceId, System.currentTimeMillis());
        listeners.forEach(l -> l.onAgentStarted(sessionKey, traceId));
    }

    public void toolCalling(String traceId, String toolName, String argsJson) {
        listeners.forEach(l -> l.onToolCalling(traceId, toolName, argsJson));
    }

    public void toolResult(String traceId, String toolName, boolean success, long costMs, String summary) {
        listeners.forEach(l -> l.onToolResult(traceId, toolName, success, costMs, summary));
    }

    public void llmCall(String traceId, int tokenIn, int tokenOut, long costMs) {
        listeners.forEach(l -> l.onLlmCall(traceId, tokenIn, tokenOut, costMs));
    }

    public void agentFinished(String traceId, String sessionKey) {
        Long start = startTime.remove(traceId);
        long cost = start == null ? 0 : System.currentTimeMillis() - start;
        listeners.forEach(l -> l.onAgentFinished(traceId, sessionKey, cost));
    }

    public void agentFailed(String traceId, String sessionKey, String reason) {
        Long start = startTime.remove(traceId);
        listeners.forEach(l -> l.onAgentFailed(traceId, sessionKey, reason));
    }
}
