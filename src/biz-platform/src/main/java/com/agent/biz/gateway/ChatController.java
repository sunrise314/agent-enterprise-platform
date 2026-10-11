package com.agent.biz.gateway;

import com.agent.biz.agent.AgentServiceImpl;
import com.agent.biz.agent.AgentServiceImpl.ChatResult;
import com.agent.biz.auth.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

/**
 * 对话接口（网关层）。
 *
 * 对应 docs/03_模块设计/03_07_gateway.md 与 docs/05_接口文档.md
 * 已接入 AgentServiceImpl 真实执行链路。
 */
@RestController
@RequestMapping("/api/agent")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AgentServiceImpl agentService;

    public ChatController(AgentServiceImpl agentService) {
        this.agentService = agentService;
    }

    /**
     * 对话（非流式）。
     */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        String tenantId = TenantContext.tenantId();
        String traceId = UUID.randomUUID().toString();
        log.info("chat request, tenant={}, sessionKey={}, trace={}",
                tenantId, request.sessionKey(), traceId);

        ChatResult r = agentService.chat(
                request.query(),
                request.sessionKey() == null ? "s-" + traceId : request.sessionKey(),
                traceId);

        return Map.of(
                "code", 0,
                "message", "ok",
                "data", Map.of(
                        "answer", r.answer(),
                        "metrics", Map.of(
                                "latencyMs", r.latencyMs(),
                                "tokenIn", r.tokenIn(),
                                "tokenOut", r.tokenOut())));
    }

    /**
     * 对话（SSE 流式）：文本增量 / 工具调用 / 完成事件实时推送。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L);
        String traceId = UUID.randomUUID().toString();
        String sessionKey = request.sessionKey() == null ? "s-" + traceId : request.sessionKey();
        agentService.streamChat(request.query(), sessionKey, traceId, emitter);
        return emitter;
    }

    public record ChatRequest(String query, String sessionKey, Boolean stream) {
    }
}
