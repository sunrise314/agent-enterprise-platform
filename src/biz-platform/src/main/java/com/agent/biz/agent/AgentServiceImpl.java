package com.agent.biz.agent;

import com.agent.biz.audit.AuditLogger;
import com.agent.biz.auth.TenantContext;
import com.agent.biz.auth.TenantInfo;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatUsage;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 执行服务：封装 Agent 构建、调用与多轮会话记忆。
 *
 * 对应 docs/03_模块设计/03_01_agent_core.md
 * 会话持久化：通过 {@link ChatMemory} 保存/回放多轮上下文，
 * 生产用 Redis，demo 用内存实现。
 */
@Service
public class AgentServiceImpl {

    private final AgentFactory factory;
    private final BizTools bizTools;
    private final AgentEventPublisher publisher;
    private final ChatMemory memory;
    private final AuditLogger audit;

    /** 单例 Agent（懒加载，复用实例与 Toolkit）。 */
    private final AtomicReference<ReActAgent> agentRef = new AtomicReference<>();

    public AgentServiceImpl(AgentFactory factory, BizTools bizTools,
                            AgentEventPublisher publisher, ChatMemory memory, AuditLogger audit) {
        this.factory = factory;
        this.bizTools = bizTools;
        this.publisher = publisher;
        this.memory = memory;
        this.audit = audit;
    }

    private ReActAgent getAgent() {
        return agentRef.updateAndGet(existing -> existing != null
                ? existing
                : factory.build("biz-assistant",
                        "你是企业智能助手。回答要简洁准确。涉及企业内部制度、流程时，"
                                + "必须先调用 kb_search 检索知识库，并基于检索结果作答，"
                                + "不要编造公司政策。",
                        bizTools.buildToolkit()));
    }

    /**
     * 对话（非流式，支持多轮会话记忆）。
     *
     * @param query      用户问题
     * @param sessionKey 会话标识（用于恢复/保存多轮上下文）
     * @param traceId    链路追踪 ID
     * @return 对话结果
     */
    public ChatResult chat(String query, String sessionKey, String traceId) {
        ReActAgent agent = getAgent();

        // 1. 读取会话历史，拼上当前问题
        List<Msg> history = memory.loadHistory(sessionKey);
        List<Msg> msgs = new ArrayList<>(history);
        msgs.add(new UserMessage(query));

        // 2. 构建运行时上下文：携带租户信息（随响应式链路注入工具，避免 ThreadLocal 跨线程丢失）
        RuntimeContext ctx = RuntimeContext.builder()
                .put(TenantInfo.class, TenantInfo.of(TenantContext.tenantId(), TenantContext.get() == null ? null : TenantContext.get().userId()))
                .userId("u-" + sessionKey.hashCode())
                .sessionId(sessionKey)
                .runId(traceId)
                .build();

        publisher.agentStarted(sessionKey, traceId);
        long start = System.currentTimeMillis();
        Msg result;
        try {
            result = agent.call(msgs, ctx).block();
        } finally {
            publisher.agentFinished(traceId, sessionKey);
        }
        long costMs = System.currentTimeMillis() - start;

        String answer = result == null ? "(无输出)" : result.getTextContent();

        // 3. 采集真实 token 用量，上报评估事件
        ChatUsage usage = result == null ? null : result.getUsage();
        int tokenIn = usage == null ? 0 : usage.getInputTokens();
        int tokenOut = usage == null ? 0 : usage.getOutputTokens();
        publisher.llmCall(traceId, tokenIn, tokenOut, costMs);

        // 4. 保存本轮到会话记忆
        memory.append(sessionKey, query, answer);

        // 5. 审计：Agent 调用留痕（租户+用户+耗时+token+结果）
        String tenant = TenantContext.tenantId();
        Long userId = TenantContext.get() == null ? null : TenantContext.get().userId();
        audit.record(tenant, userId, "agent.chat", sessionKey,
                "q=" + truncate(query, 60) + " | a=" + truncate(answer, 60)
                        + " | tok=" + tokenIn + "/" + tokenOut, true, costMs);

        return new ChatResult(answer, costMs, tokenIn, tokenOut);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * 流式对话（SSE）。订阅 agent.streamEvents，把文本增量 / 工具调用实时推给 SseEmitter，
     * 完成时保存会话记忆、审计并返回完整答案。
     *
     * @param query      用户问题
     * @param sessionKey 会话标识
     * @param traceId    链路追踪 ID
     * @param emitter    SSE 发射器
     */
    public void streamChat(String query, String sessionKey, String traceId, SseEmitter emitter) {
        ReActAgent agent = getAgent();
        List<Msg> history = memory.loadHistory(sessionKey);
        List<Msg> msgs = new ArrayList<>(history);
        msgs.add(new UserMessage(query));

        RuntimeContext ctx = RuntimeContext.builder()
                .put(TenantInfo.class, TenantInfo.of(TenantContext.tenantId(), TenantContext.get() == null ? null : TenantContext.get().userId()))
                .userId("u-" + sessionKey.hashCode())
                .sessionId(sessionKey)
                .runId(traceId)
                .build();

        StringBuilder full = new StringBuilder();
        long[] cost = {0};
        int[] tokenIn = {0};
        int[] tokenOut = {0};
        long start = System.currentTimeMillis();

        publisher.agentStarted(sessionKey, traceId);
        agent.streamEvents(msgs, ctx)
                .doOnNext(event -> {
                    if (event instanceof TextBlockDeltaEvent d) {
                        full.append(d.getDelta());
                        send(emitter, "delta", java.util.Map.of("text", d.getDelta()));
                    } else if (event instanceof ToolCallStartEvent t) {
                        send(emitter, "tool", java.util.Map.of("name", t.getToolCallName()));
                    } else if (event instanceof AgentResultEvent ar) {
                        Msg result = ar.getResult();
                        ChatUsage usage = result == null ? null : result.getUsage();
                        if (usage != null) {
                            tokenIn[0] = usage.getInputTokens();
                            tokenOut[0] = usage.getOutputTokens();
                        }
                    }
                })
                .doOnError(err -> {
                    publisher.agentFailed(traceId, sessionKey, err.getMessage());
                    try {
                        emitter.completeWithError(err);
                    } catch (Exception ignore) {
                        // SSE already closed
                    }
                })
                .doOnComplete(() -> {
                    cost[0] = System.currentTimeMillis() - start;
                    publisher.agentFinished(traceId, sessionKey);
                    memory.append(sessionKey, query, full.toString());
                    publisher.llmCall(traceId, tokenIn[0], tokenOut[0], cost[0]);
                    String tenant = TenantContext.tenantId();
                    Long userId = TenantContext.get() == null ? null : TenantContext.get().userId();
                    audit.record(tenant, userId, "agent.chat.stream", sessionKey,
                            "q=" + truncate(query, 40) + " | tok=" + tokenIn[0] + "/" + tokenOut[0],
                            true, cost[0]);
                    send(emitter, "done", java.util.Map.of(
                            "answer", full.toString(),
                            "latencyMs", cost[0],
                            "tokenIn", tokenIn[0],
                            "tokenOut", tokenOut[0]));
                    try {
                        emitter.complete();
                    } catch (Exception ignore) {
                        // already completed
                    }
                })
                .subscribe();
    }

    private void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception ignore) {
            // client disconnected
        }
    }

    /** 对话结果载体。 */
    public record ChatResult(String answer, long latencyMs, int tokenIn, int tokenOut) {
    }
}
