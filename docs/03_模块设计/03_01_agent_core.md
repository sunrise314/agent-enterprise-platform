# 03_01 Agent 核心模块（Agent Core）

> 基于 AgentScope-Java 底座扩展，作为整个平台的智能体执行内核。

## 3.1.1 模块职责

- 创建 / 管理 HarnessAgent 与 ReActAgent 实例
- 负责 ReAct 推理循环、工具调度
- 会话 State 管理与持久化（Redis）
- 事件流发布（供评估、监控消费）
- 安全控制（最大工具调用轮次、超时、防死循环）

## 3.1.2 底座能力（AgentScope-Java 原生）

| 能力 | 说明 |
|---|---|
| HarnessAgent | 生产级 Agent，带会话持久化、Workspace、子 Agent、权限隔离、沙箱、上下文压缩 |
| ReActAgent | 轻量 ReAct 工具调用 Agent，适合学习原理 |
| MCP 协议 | 外部工具注册与调用 |
| A2A | 多智能体协作（v1.1 启用） |
| 事件流 | Agent 全生命周期事件上报 |
| 模型适配 | 通义千问、OpenAI、Ollama、vLLM |

## 3.1.3 自研扩展点

### a) 统一 Agent 工厂（AgentFactory）

- 根据配置构建不同 Agent 实例
- 统一注入系统提示词、模型、工具集、事件监听器

### b) Agent 实例池 + 会话绑定

- 为每个租户 / 会话维护独立 RuntimeContext
- sessionId → Agent 实例映射，支持多会话并发

### c) 安全守卫（AgentGuard）

- 最大工具调用轮次限制（默认 8 次），防止死循环
- 单次 Agent 执行超时控制（默认 60s）
- LLM 调用失败重试策略（指数退避）

### d) 事件监听器（AgentEventListener）

- 订阅：AgentStarted / ToolCalling / ToolResult / AgentFinished / AgentFailed
- 事件异步写入评估模块与监控模块，不阻塞主链路

## 3.1.4 关键流程（ReAct 循环）

```
用户输入
  → 系统提示词 + 工具定义注入
  → LLM 推理（ReAct 模式）
  → 输出 Action（调用哪个工具 + 参数）
  → 执行工具，返回 Observation
  → 再次 LLM 推理，判断是否完成 / 继续
  → 循环直到 Final Answer 或达到最大轮次
```

## 3.1.5 核心代码结构

```
com.agent.biz.agent
├── AgentFactory.java          # Agent 工厂
├── AgentManager.java          # Agent 实例池与会话绑定
├── AgentGuard.java            # 安全守卫（轮次/超时/重试）
├── AgentEventListener.java    # 事件监听接口
├── AgentEventPublisher.java   # 事件发布器
└── AgentServiceImpl.java      # 对外 Agent 执行服务
```

## 3.1.6 面试要点（Q&A 速记）

- **Q：为什么用 HarnessAgent 而不是 ReActAgent？**
  答：HarnessAgent 是生产级底座，自带会话持久化、Workspace、上下文压缩、沙箱、权限隔离，ReActAgent 更适合原型学习。
- **Q：怎么防止 Agent 死循环？**
  答：AgentGuard 设置最大工具调用轮次（默认 8），超过直接终止并返回超限错误；同时设置单次执行总超时。
- **Q：事件流有什么用？**
  答：全链路可观测 + 评估数据采集，Agent 每一步思考、工具调用、结果都能被追踪，为量化评估提供原始数据。

## 3.1.9 真实 API 与当前实现状态（2026-10-10 已验证编译通过）

**底座版本**：`io.agentscope:agentscope-harness:2.1.0-SNAPSHOT`（传递依赖 core），已 install 到本地仓库，`biz-platform` 编译通过。

**已实现代码（Agent 核心闭环）：**
| 文件 | 状态 |
|---|---|
| `agent/AgentFactory.java` | ✅ 基于 ReActAgent.builder 真实 API |
| `agent/BizTools.java` | ✅ @Tool 注解业务工具（kb_search / get_current_time） |
| `agent/AgentServiceImpl.java` | ✅ call + 旁路上报事件 |
| `agent/AgentGuard.java` / `AgentEventListener` / `AgentEventPublisher` | ✅ |
| `rag/RagService.java` | 骨架占位（RAG 模块迭代接真实检索） |
| `gateway/ChatController.java` | ✅ /api/agent/chat 接入真实执行 |

**关键真实 API（编码依据官方 ToolCallingExample / BasicChatExample）：**
- 构建：`ReActAgent.builder().name().sysPrompt().model("dashscope:qwen-plus").toolkit().middleware(new AgentTraceMiddleware()).build()`
- 工具：`@Tool(name, description, readOnly)` + `@ToolParam`，`toolkit.registerTool(instance)`
- 调用：`agent.call(new UserMessage(q)).block()` → `Msg.getTextContent()`
- 流式：`agent.streamEvents(msg).doOnNext(e -> e instanceof TextBlockDeltaEvent)` → `.blockLast()`
- 模型：`"dashscope:qwen-plus"` 由 ModelRegistry 自动解析，API Key 读环境变量 `DASHSCOPE_API_KEY`

**待办（后续迭代）：**
- ✅ ~~会话状态持久化~~ 已实现（见 3.1.11）
- 接入 `result.getUsage()` 采集 token 上报评估模块
- HarnessAgent 生产级能力（Workspace / Memory 压缩 / 沙箱）在 v1.1 引入

## 3.1.11 会话持久化（ChatMemory，已实现并验证）

**设计：应用层会话记忆，不依赖 AgentScope 复杂 event-log SPI**
- 接口 `ChatMemory`：`loadHistory(sessionKey)` / `append(...)` / `clear(...)`
- 实现：
  - `RedisChatMemory`（`@Profile("!demo")` 生产）：Redis String，key `chat:mem:{sessionKey}`，值 JSON `[{role,content}]`，带 TTL，按 `agent.session.max-turns` 裁剪最旧轮次
  - `InMemoryChatMemory`（`@Profile("demo")`）：进程内，用于无 Redis 的本地 demo
- `AgentServiceImpl.chat`：读历史 → 拼当前 `UserMessage` → `agent.call(List<Msg>, RuntimeContext)` → 用返回 `Msg.getTextContent()` 存回

**关键 API（已确认）：**
- `new UserMessage(text)` / `new AssistantMessage(text)` 构造历史消息
- `RuntimeContext.builder().userId().sessionId().runId().build()`
- `agent.call(List<Msg> msgs, RuntimeContext context).block()`

**验证结果（demo/InMemory）：**
- 第一轮：告知名字 → 存入记忆
- 第二轮：同一 sessionKey 问名字 → Agent 正确回答"你叫张伟" ✅

**说明：** RedisChatMemory 代码已就绪，生产环境启用；实机验证 Redis 持久化需本机有 Redis（`docker run -p 6379:6379 redis` 或装 Memurai）。

## 3.1.10 运行验证（2026-10-10 已跑通）

**模型：DeepSeek（deepseek:deepseek-chat）**
- 依赖：`io.agentscope:agentscope-extensions-model-openai`（含 DeepSeek provider，ServiceLoader 自动注册 `deepseek:` 前缀）
- key：环境变量 `DEEPSEEK_API_KEY`，默认 base_url `https://api.deepseek.com`
- 说明：原计划 DashScope，但用户 key 被 DashScope 返回 403（非百炼 key），已切换 DeepSeek

**验证结果：**
- ✅ `mvn spring-boot:run -DSPRING_PROFILES_ACTIVE=demo` 启动成功（H2 内存库）
- ✅ `POST /api/agent/chat` 返回 HTTP 200 / CODE=0，DeepSeek 997ms 返回答案
- ✅ Toolkit 工具注册成功：`kb_search`、`get_current_time`
- ✅ Agent ReAct 循环、事件发布、旁路上报均工作
- ⚠️ 本次 DeepSeek 未主动触发 `kb_search`（模型行为），需优化系统提示词引导工具调用

**踩坑记录：**
1. AgentScope 用 `dashscope:qwen-plus` 需 dashscope 扩展，否则报 `Cannot resolve model`
2. DashScope 用户 key 403 → 切 DeepSeek（openai 扩展）
3. `-Dspring-boot.run.profiles=demo` 在 PowerShell 下参数解析失败 → 改用环境变量 `SPRING_PROFILES_ACTIVE=demo`
4. 测试脚本 Invoke-RestMethod 发中文 body 需显式 UTF-8，否则模型收到乱码
