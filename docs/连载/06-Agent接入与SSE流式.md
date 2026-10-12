# 06 Agent 接入与 SSE 流式输出

> 第六篇讲 Agent 怎么跑起来：ReAct 编排、自定义 Tool、SSE 流式三类事件。

## AgentFactory：一行代码构建 ReActAgent

AgentScope-Java 的 ReActAgent 是整个平台的执行内核。我的 `AgentFactory` 把构建过程封装成一行：

```java
public ReActAgent build(String agentName, String sysPrompt, Toolkit toolkit) {
    ReActAgent.Builder builder = ReActAgent.builder()
            .name(agentName)
            .sysPrompt(sysPrompt)
            .model(model)           // deepseek:deepseek-chat
            .middleware(new AgentTraceMiddleware());  // 事件追踪
    if (toolkit != null) {
        builder.toolkit(toolkit);
    }
    return builder.build();
}
```

关键点在 `AgentTraceMiddleware`——这是底座事件流的入口，Agent 每次思考、调工具、结束都会通过它抛出事件，被我的 `AgentEventPublisher` 旁路消费（评估、监控、审计都从这来）。整个主链路对旁路零感知，前一章架构图里的"旁路采集"就是这个意思。

## 自定义 Tool：@Tool 注解式注册

业务工具（kb_search、sql_query、file_export）通过 AgentScope 的 Tool 接口注册进 Agent。我用注解式简化了注册：

```java
// BizTools.java
@Tool(description = "搜索企业知识库，返回相关文档片段")
public String kb_search(String query, String tenantId) {
    return ragService.searchSummary(query, 5, tenantId);
}
```

AgentScope 会在启动时扫描 `@Tool` 注解，自动把方法签名转换成 JSON Schema 喂给 LLM，LLM 在 ReAct 循环里判断要不要调这个工具、传什么参数。这一步完全不用手写 prompt。

SQL 沙箱工具 `sql_query` 额外加了一层 `SqlGuard`：正则拦截 DDL/DML、LIMIT 强制加、结果行数限制 100。Agent 永远写不了脏数据。

## SSE 流式：三类事件

同步调用 `agent.call().block()` 会等完整答案返回。企业场景需要流式——用户不想等 3 秒才能看到第一个字。我的 SSE 实现通过底座的事件流旁路：

| 事件类型 | 触发时机 | SSE 输出 |
|---|---|---|
| `think_start` | Agent 开始思考 | 状态事件 |
| `think_delta` | LLM 增量输出思考内容 | 流式 delta |
| `tool_call` | Agent 决定调用工具 | 工具名 + 参数 |
| `tool_result` | 工具执行完毕 | 工具返回内容 |
| `answer_delta` | 最终答案增量 | 流式 delta |
| `done` | Agent 执行完毕 | 完整答案 + token 统计 |

SSE endpoint 是 `POST /api/agent/chat/stream`，返回 `text/event-stream`。实测一轮对话的事件序列：

```
event: think_start
event: think_delta   data: {"content": "用户问的是年假制度..."}
event: tool_call     data: {"tool": "kb_search", "args": {"query": "年假制度"}}
event: tool_result   data: {"content": "年假制度 员工满1年不满10年..." }
event: answer_delta  data: {"content": "根据公司年假制度，员工满1年不满10年的..." }
event: done          data: {"answer": "...", "tokenIn": 888, "tokenOut": 89, "latency": 2768}
```

## 实测数据

- 单轮对话延迟：同步 2768ms / 流式首个 delta 出现在 412ms；
- ReAct 循环轮次：平均 2.3 轮（思考 → 调工具 → 再思考 → 输出），最多 4 轮；
- Tool 调用准确率：实测 10 次，9 次调对工具、参数正确，1 次参数格式错被 AgentScope 自动重试纠正。

下一篇进会话记忆：为什么放弃上游 event-log SPI、ChatMemory 抽象怎么设计、Redis TTL 防膨胀。

---

- 本章对应源码：`AgentFactory.java`、`ChatController.java`（SSE）、`BizTools.java`
- 配套文档：`docs/03_模块设计/03_01_agent_core.md`
