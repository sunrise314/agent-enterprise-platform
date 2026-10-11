package com.agent.biz.agent;

import io.agentscope.core.message.Msg;

import java.util.List;

/**
 * 会话记忆抽象：保存/恢复 Agent 多轮对话上下文。
 *
 * 对应 docs/03_模块设计/03_01_agent_core.md「会话持久化」。
 * 实现：
 *  - RedisChatMemory：生产实现（Redis 存多轮消息 JSON + TTL）
 *  - InMemoryChatMemory：demo 实现（无 Redis 环境跑通逻辑）
 */
public interface ChatMemory {

    /** 加载某会话的历史消息（按时间正序，user/assistant 交替）。 */
    List<Msg> loadHistory(String sessionKey);

    /** 追加一轮（用户消息 + 助手回复），并裁剪到最大轮数。 */
    void append(String sessionKey, String userText, String assistantText);

    /** 清空会话。 */
    void clear(String sessionKey);
}
