# 08 多租户隔离：ThreadLocal 在 Reactor 里的坑

> 第八篇讲多租户隔离。这一章有个具体的工程坑：ThreadLocal 在 Reactor boundedElastic 线程池里会失效，我是怎么绕过去的。

## 问题：ThreadLocal 丢了租户上下文

我的网关用 Spring MVC + Project Reactor，SSE 流式场景下用的是 `boundedElastic` 线程池。最开始我用 ThreadLocal 存租户上下文：

```java
// 错误示范：ThreadLocal 在 boundedElastic 里会丢
TenantContext.set(tenant, userId, null);  // 请求开始时设置
// ... boundedElastic 线程里跑 Agent
TenantContext.getTenantId();  // 返回 null！
```

原因很简单：`boundedElastic` 里的任务会被线程池调度到不同的物理线程上，ThreadLocal 变量跟着线程走不跟着任务走——A 线程 set 的，B 线程读不到。

## 解法：RuntimeContext 链路注入

AgentScope 的 ReActAgent 执行时会传一个 `RuntimeContext` 参数，它本质上是一个 `Map<String, Object>`，跟着调用链走。我把租户、用户信息塞进这个 Map，让它从网关一路透传到 Agent、Tool、RAG 检索：

```java
// TenantInterceptor：请求入口解析租户，塞进 ThreadLocal 再桥接进 RuntimeContext
TenantContext.set(tenant, userId, null);

// Agent 执行时从 ThreadLocal 取出，塞进 RuntimeContext
RuntimeContext ctx = RuntimeContext.builder()
        .sessionId(sessionId)
        .userId(String.valueOf(userId))
        .build();
ctx.put("tenantId", tenant);  // 关键：租户上下文放进 RuntimeContext
agent.call(messages, ctx);
```

然后在工具和检索里，从 RuntimeContext 取租户 ID 做隔离：

```java
// RagService.searchSummary()
String tenantId = ctx.get("tenantId", String.class);
retriever.retrieve(query, topK, tenantId);
```

## 两层隔离验证

| 层 | 隔离方式 | 验证 |
|---|---|---|
| Milvus Collection | `kb_{tenant}` 命名 | 租户 A 永远搜不到租户 B 的向量 |
| Redis 会话 | `chat:mem:{tenant}:{user}:...` key | 租户 A 的会话租户 B 看不到 |
| Token 计费 | 审计行带 `tenant_id` | 按租户对账不串账 |

实测隔离：租户 `tenant-1` 上传员工手册，租户 `tenant-2` 用同一个问题检索，返回「(未检索到相关文档，请如实告知用户知识库中暂无此信息。)」——隔离生效。

## TenantInterceptor：请求入口的防线

拦截器做的事很简单：从请求头 `X-Tenant-Id`、`X-User-Id` 取值，塞进 ThreadLocal（供后续桥接进 RuntimeContext），请求结束清理。

```java
public boolean preHandle(HttpServletRequest request, ...) {
    String tenant = request.getHeader(HEADER_TENANT);
    if (tenant == null || tenant.isBlank()) {
        tenant = DEFAULT_TENANT;  // 兼容 demo，生产应强制租户
    }
    TenantContext.set(tenant, userId, null);
    return true;
}

public void afterCompletion(HttpServletRequest request, ...) {
    TenantContext.clear();  // 必须清，否则 ThreadLocal 内存泄漏
}
```

生产环境应该强制租户头存在，不传就 403。demo 态下宽松处理（DEFAULT_TENANT = tenant-0），方便本地调试。

下一篇进认证、权限与审计：JWT 签发校验、路径→权限映射、审计埋点选点。

---

- 本章对应源码：`TenantInterceptor.java`、`TenantContext.java`、`MilvusHybridRetriever.collectionName()`
- 配套文档：`docs/03_模块设计/03_05_auth_rbac.md`
