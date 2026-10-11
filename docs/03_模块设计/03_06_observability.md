# 03_06 可观测模块（Observability）

> 全链路可观测 + 指标采集，让 Agent 系统「可监控、可排查、可度量」。

## 3.6.1 模块职责

- Agent 全生命周期事件埋点
- 链路追踪（trace 串联用户请求 → Agent → 工具 → LLM）
- 日志收集与结构化
- 指标采集与上报（Prometheus）
- Grafana 可视化看板

## 3.6.2 埋点事件（Agent 生命周期）

| 事件 | 说明 |
|---|---|
| AgentStarted | Agent 开始执行 |
| ToolCalling | 工具调用中（含工具名、入参） |
| ToolResult | 工具返回（含耗时、成功/失败） |
| LlmCall | LLM 调用（含 token、延迟） |
| AgentFinished | Agent 完成 |
| AgentFailed | Agent 失败（含错误原因） |

## 3.6.3 链路追踪设计

```
Trace = 一次用户请求
  Span 1: 网关鉴权
  Span 2: Agent 执行
    Span 2.1: LLM 推理
    Span 2.2: 工具调用（kb_search / db_query ...）
    Span 2.3: RRF 重排
  Span 3: 指标采集
```

- 用 TraceId 串联整条链路
- 每个 Span 记录：开始/结束时间、状态、关键字段
- 支持按 TraceId 排查线上问题（哪一步失败）

## 3.6.4 指标采集（Prometheus）

**业务指标：**
- 任务完成率、工具调用准确率、幻觉率（来自评估模块上报）

**性能指标：**
- P50 / P95 端到端延迟
- LLM 调用延迟、token 消耗
- 工具调用耗时

**稳定性指标：**
- 错误率、重试率、限流丢弃数、超时数

## 3.6.5 日志规范

- 结构化 JSON 日志
- 关键字段：traceId、sessionId、userId、tenantId、model、toolName
- 级别：ERROR 记录失败、WARN 记录降级/重试、INFO 记录链路

## 3.6.6 告警

- 延迟 P95 超阈值
- 错误率突增
- 工具调用失败率过高
- LLM 限流 / 配额告警

## 3.6.7 核心代码结构

```
com.agent.biz.agent
└── AgentEventListener.java     # 事件监听（上报埋点）
com.agent.biz.gateway
└── TraceFilter.java            # 网关 TraceId 注入
```
（指标上报组件可在 `com.agent.biz.obs` 包中扩展）

## 3.6.8 面试要点（Q&A 速记）

- **Q：事件埋点为什么用旁路异步？**
  答：采集不阻塞主链路，避免影响用户体验；事件写入消息队列 / 异步线程池处理。
- **Q：怎么定位线上 Agent 失败？**
  答：按 TraceId 拉取完整链路，看是 LLM 超时、工具参数错、还是重排返回空，逐 Span 定位。
- **Q：token 指标为什么重要？**
  答：企业关注成本。token 消耗直接对应调用成本，可量化上报做成本审计。

## 3.6.9 指标采集落地（已实现并验证）

**已实现：指标订阅聚合 + 查询接口，可对接 Grafana。**

**代码：**
```
com.agent.biz.metrics
├── MetricsStore.java          # 指标存储抽象（llm/agent/tool + summary/series）
├── InMemoryMetricsStore.java  # demo 实现（按分钟聚合，环形缓冲 240 分钟）
├── MetricsSubscriber.java     # 订阅 Agent 事件 → 写入 store（@PostConstruct 注册）
└── MetricsController.java     # GET /api/metrics/summary、/api/metrics/series
```

**采集维度（订阅 AgentEventPublisher 旁路事件）：**
- LLM 调用：tokenIn / tokenOut / 耗时（`llmCall`）
- Agent 调用：成功/失败/耗时（`agentFinished` / `agentFailed`）
- 工具调用：成功/失败/耗时（`toolResult`）

**接口：**
- `GET /api/metrics/summary?windowMs=300000` → `{calls, llmCalls, tokenIn, tokenOut, failedCalls, p95LatencyMs, avgLatencyMs}`
- `GET /api/metrics/series` → 按分钟时序点（折线图用）

**验证结果（demo）：**
```
calls=1  llmCalls=1  tokenIn=934  tokenOut=145  p95LatencyMs=5104  failedCalls=0
```

**Grafana 对接：**
- demo：用 Grafana **JSON API 数据源**指向 `/api/metrics/series`（或导出 CSV）
- 生产：替换 `InMemoryMetricsStore` 为 InfluxDB/PostgreSQL(时序) 写入，配合 **Telegraf 采集 + Prometheus 拉取**，Grafana 建看板（P95 延迟 / token 成本 / 错误率 / 工具失败率）
- 告警（3.6.6）由 Grafana alert 触发：延迟 P95 超阈值、错误率突增、工具失败率过高、token 成本超预算

**设计要点（面试）：**
- 指标采集走 AgentEventPublisher **旁路异步**，不阻塞 Agent 主链路（与评估监听器复用同一事件流）
- MetricsStore 抽象隔离存储实现，demo(内存) ↔ 生产(InfluxDB/PostgreSQL) 可无侵入替换
