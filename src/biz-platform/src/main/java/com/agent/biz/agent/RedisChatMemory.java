package com.agent.biz.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis 会话记忆（生产实现）。
 *
 * 存储：Redis String，key = {@code chat:mem:{sessionKey}}
 * 值：JSON 数组 [{role:"user|assistant", content:"..."}]
 * 附带 TTL（会话过期时间），超过 maxTurns 裁剪最旧轮次。
 *
 * 对应 docs/03_模块设计/03_01_agent_core.md「会话持久化」
 */
@Profile("!demo & !milvus")
@Component
public class RedisChatMemory implements ChatMemory {

    private static final String KEY_PREFIX = "chat:mem:";

    private final StringRedisTemplate redis;
    private final ObjectMapper om;
    private final int maxTurns;
    private final long ttlSeconds;

    public RedisChatMemory(
            StringRedisTemplate redis,
            ObjectMapper om,
            @Value("${agent.session.max-turns:10}") int maxTurns,
            @Value("${agent.session.ttl-seconds:86400}") long ttlSeconds) {
        this.redis = redis;
        this.om = om;
        this.maxTurns = maxTurns;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public List<Msg> loadHistory(String sessionKey) {
        String raw = redis.opsForValue().get(key(sessionKey));
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        try {
            JsonNode arr = om.readTree(raw);
            List<Msg> msgs = new ArrayList<>();
            for (JsonNode node : arr) {
                String role = node.path("role").asText();
                String content = node.path("content").asText();
                if ("assistant".equals(role)) {
                    msgs.add(new AssistantMessage(content));
                } else {
                    msgs.add(new UserMessage(content));
                }
            }
            return msgs;
        } catch (Exception e) {
            // 反序列化失败则视为无历史，避免单个坏数据阻塞对话
            return new ArrayList<>();
        }
    }

    @Override
    public void append(String sessionKey, String userText, String assistantText) {
        List<Msg> history = loadHistory(sessionKey);
        history.add(new UserMessage(userText));
        history.add(new AssistantMessage(assistantText));

        // 裁剪到最大轮数
        int maxMsgs = maxTurns * 2;
        if (history.size() > maxMsgs) {
            history = history.subList(history.size() - maxMsgs, history.size());
        }

        String json = toJson(history);
        if (json != null) {
            redis.opsForValue().set(key(sessionKey), json, Duration.ofSeconds(ttlSeconds));
        }
    }

    @Override
    public void clear(String sessionKey) {
        redis.delete(key(sessionKey));
    }

    private String key(String sessionKey) {
        return KEY_PREFIX + sessionKey;
    }

    private String toJson(List<Msg> msgs) {
        try {
            List<JsonNode> nodes = new ArrayList<>();
            for (Msg m : msgs) {
                String text = m.getTextContent();
                nodes.add(om.createObjectNode()
                        .put("role", "assistant".equals(m.getRole().name())
                                ? "assistant" : "user")
                        .put("content", text == null ? "" : text));
            }
            return om.writeValueAsString(nodes);
        } catch (Exception e) {
            return null;
        }
    }
}
