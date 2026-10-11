package com.agent.biz.agent;

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存会话记忆（demo 环境，进程内，重启即失）。
 *
 * 仅用于无 Redis 的本地 demo 跑通多轮对话逻辑；
 * 生产环境使用 {@link RedisChatMemory}。
 */
@Profile({"demo", "milvus"})
@Component
public class InMemoryChatMemory implements ChatMemory {

    private final ConcurrentMap<String, List<Msg>> store = new ConcurrentHashMap<>();
    private final int maxTurns;

    public InMemoryChatMemory(@Value("${agent.session.max-turns:10}") int maxTurns) {
        this.maxTurns = maxTurns;
    }

    @Override
    public List<Msg> loadHistory(String sessionKey) {
        return new ArrayList<>(store.getOrDefault(sessionKey, List.of()));
    }

    @Override
    public void append(String sessionKey, String userText, String assistantText) {
        List<Msg> list = store.computeIfAbsent(sessionKey, k -> new ArrayList<>());
        synchronized (list) {
            list.add(new UserMessage(userText));
            list.add(new AssistantMessage(assistantText));
            trim(list);
        }
    }

    @Override
    public void clear(String sessionKey) {
        store.remove(sessionKey);
    }

    private void trim(List<Msg> list) {
        int maxMsgs = maxTurns * 2;
        while (list.size() > maxMsgs) {
            list.remove(0);
        }
    }
}
