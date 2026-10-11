# 03_07 API 网关模块（Gateway）

> 统一的接入入口，负责鉴权、限流、输入校验、流式输出、路由。

## 3.7.1 模块职责

- 统一鉴权（解析 Token，注入租户 / 用户上下文）
- 限流 / 熔断（保护下游 Agent 与 LLM）
- 输入校验（防注入、参数校验）
- 流式输出（SSE，Agent 结果边生成边推送）
- 请求路由与日志

## 3.7.2 核心能力

### a) 鉴权
- 拦截器解析 JWT，校验有效性
- 注入 `TenantContext`（tenantId / userId / roles）到 ThreadLocal / Reactor Context

### b) 限流 & 熔断
- 令牌桶 / 滑动窗口限流（按用户、按租户、按 IP）
- LLM 调用侧限流（防止大并发打爆模型服务）
- 熔断：连续失败达到阈值，快速失败降级

### c) 输入校验
- 请求参数校验
- Prompt 注入检测（输入过滤）
- 长度限制（防止超大输入）

### d) 流式输出（SSE）
- Agent 逐步输出思考 / 工具调用状态 / 最终答案
- 前端可实时展示进度

## 3.7.3 主要接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/agent/chat` | 对话（支持流式 SSE） |
| POST | `/api/agent/session/{id}/chat` | 指定会话对话 |
| GET | `/api/agent/session` | 会话列表 |
| POST | `/api/kb/upload` | 上传文档入库 |
| POST | `/api/kb/search` | 知识库检索（调试用） |
| POST | `/api/eval/run` | 触发评估 |
| GET | `/api/eval/report/{id}` | 查看评估报告 |

## 3.7.4 核心代码结构

```
com.agent.biz.gateway
├── AuthFilter.java        # 鉴权过滤器
├── RateLimitFilter.java   # 限流过滤器
├── InputGuardFilter.java  # 输入校验 / 防注入
├── TraceFilter.java       # TraceId 注入
├── SseEmitterHelper.java  # 流式输出封装
└── ChatController.java    # 对话接口
```

## 3.7.5 面试要点（Q&A 速记）

- **Q：SSE 流式输出怎么实现？**
  答：Spring 的 SseEmitter + AgentScope `agent.streamEvents(msgs, ctx)`（返回 `Flux<AgentEvent>`）。订阅事件流，把 `TextBlockDeltaEvent`（文本增量）推为 `delta` 事件、`ToolCallStartEvent`（工具名）推为 `tool` 事件、完成时推 `done` 并落记忆/审计。客户端用 EventSource 接收。

**SSE 流式对话（已验证）实现说明：**
- `POST /api/agent/chat/stream` → 创建 `SseEmitter` → `AgentServiceImpl.streamChat` 订阅 `streamEvents`
- 事件映射：`TextBlockDeltaEvent→delta`、`ToolCallStartEvent→tool`、`AgentResultEvent→usage`、完成→`done`（含 answer/latencyMs/token）
- 验证：`delta_events=122`、`tool_events=1`（kb_search）、`done_events=1`，顺序正确（先 delta → tool → 基于检索的 delta）
- 启动方式变更：依赖增多后 `mvn spring-boot:run` 遇 Windows 命令行超长（CreateProcess error=206），改用 `mvn package -DskipTests` + `java -jar target/biz-platform-1.0.0.jar`
- **Q：LLM 限流和业务限流区别？**
  答：业务限流保护系统资源；LLM 限流防止超并发打爆模型服务或触发配额超限，两套限流独立配置。
- **Q：鉴权怎么和 Agent 执行打通？**
  答：网关解析 Token 后把 tenantId / userId / roles 注入上下文，Agent 执行前按角色校验工具权限。
