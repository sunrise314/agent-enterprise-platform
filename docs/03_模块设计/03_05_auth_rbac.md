# 03_05 权限 & 审计模块（Auth / RBAC）

> 多租户隔离 + RBAC 权限 + 操作审计，政企私有化交付的硬性安全要求。

## 3.5.1 模块职责

- 用户认证（账号密码 / 对接企业 SSO）
- RBAC 权限模型（用户 / 角色 / 权限）
- 多租户隔离（租户级数据隔离）
- 会话级权限校验（Agent 执行前检查）
- 操作审计日志（所有 Agent 调用、工具调用、管理操作）

## 3.5.2 RBAC 模型

```
用户(User) ──多对多── 角色(Role) ──多对多── 权限(Permission)
权限粒度：资源 + 操作
示例：知识库检索 / 业务查询 / 文件导出 / 系统管理
```

**资源隔离：**
- 租户隔离：不同租户数据完全隔离
- 知识库隔离：用户只能访问授权知识库
- 工具权限：角色决定可用工具集（如普通用户无 SQL 查询权限）

## 3.5.3 认证流程

```
用户登录 → 校验凭证（或 SSO）→ 签发 JWT / Token
  → 每次请求网关校验 Token → 解析 userId / tenantId / roles
  → 构建 RuntimeContext 注入 Agent
  → Agent 执行前按角色校验工具权限
```

## 3.5.4 审计日志（重点）

**审计内容：**
- 用户操作：登录 / 登出 / 权限变更
- Agent 调用：请求、会话、租户、模型、耗时
- 工具调用：工具名、入参、出参摘要、执行人、SQL 语句
- 管理操作：知识库增删改、配置变更

**审计要求：**
- 只追加，不可篡改
- 记录操作人（userId + 租户）、时间、来源 IP
- 支持导出，满足政企审计合规

## 3.5.5 会话安全

- 会话状态按租户隔离存储（Redis key 带 tenantId）
- Prompt 注入防护：输入过滤 + 系统提示词强约束 + 检索结果边界
- 敏感信息脱敏：工具出参对敏感字段脱敏

## 3.5.6 核心代码结构

```
com.agent.biz.auth
├── AuthService.java        # 认证
├── RbacService.java        # 角色权限校验
├── TenantContext.java      # 租户上下文
├── AuditLogger.java        # 审计日志
├── PromptGuard.java        # Prompt 注入防护
└── SensitiveMasker.java    # 敏感字段脱敏
```

## 3.5.7 面试要点（Q&A 速记）

- **Q：多租户怎么隔离数据？**
  答：所有业务表带 tenantId 字段，检索 / 查询强制附加租户过滤；Redis 会话 key 带租户前缀；向量库查询带租户过滤。
- **Q：审计日志为什么重要？**
  答：政企安全合规要求操作可追溯，尤其工具执行 SQL、文件导出等高危操作必须留痕。
- **Q：Prompt 注入怎么防护？**
  答：输入过滤（拦截注入模式）+ 系统提示词强化边界 + 检索文档内容与指令隔离 + 工具白名单。

## 3.5.8 当前实现状态（已落地并验证）

**核心能力：多租户隔离已实现并验证通过；RBAC 权限模型已就绪。**

**租户隔离（已验证）：**
- 每个 `KnowledgeDocument` 带 `tenantId`，检索按租户过滤
- `TenantInterceptor`：从请求头 `X-Tenant-Id` 解析租户，注入 `TenantContext`（无头默认 `tenant-0`，生产必须强制）
- **关键设计**：租户通过 `RuntimeContext.builder().put(TenantInfo.class, ...)` 随 Agent 响应式链路传给工具方法（非 `@ToolParam` 参数自动注入），**避免 ThreadLocal 在 Reactor 跨线程丢失**（实测直接踩坑）

**RBAC 模型（已就绪）：**
- `AuthService`：用户→角色→权限（`ADMIN`/`EMPLOYEE`，`agent:chat`/`kb:index` 等）
- `hasPermission(userId, permission)` 供接口鉴权调用；生产接 JWT 后由网关解析

**代码：**
```
com.agent.biz.auth
├── TenantContext.java    # 租户上下文（ThreadLocal，请求级）
├── TenantInfo.java       # 租户载体，随 RuntimeContext 注入工具
├── TenantInterceptor.java# 请求头 → TenantContext
├── AuthService.java      # RBAC 角色权限
└── gateway/WebMvcConfig  # 注册拦截器
```

**验证结果（demo，同一问题不同租户）：**
```
tenant-0 问年假 → 命中【员工手册】（tenant-0 文档）
tenant-1 问年假 → 检索不到（tenant-1 无此文档）✅ 隔离生效
tenant-1 问出差 → 命中【出差政策】（tenant-1 专属）✅
```
关键证据：tenant-1 提问年假时，Agent 明确"知识库里只检索到出差政策"——证明无法触达 tenant-0 文档。

**实战坑点（面试重点）：**
- **ThreadLocal 不能跨 Reactor 线程**：工具在 boundedElastic 执行，读不到请求线程的租户 → 必须用 `RuntimeContext.put(TenantInfo.class, ...)` 随响应式链路传递
- 检索过滤由调用方显式传 `tenantId`，而非内部读全局上下文，职责更清晰

**待办（生产）：**
- 会话 Redis key 带租户前缀；向量库查询带租户过滤
- 审计日志落库 PostgreSQL/ES + 导出

## 3.5.9 商用硬缺口补齐（审计 + token + JWT/RBAC，已实现并验证）

**审计日志（已验证）：**
- `AuditLogger`：记录 Agent 调用 + 工具调用，带租户/用户/操作/目标/结果/耗时/详情
- 埋点：`agent.chat`（AgentServiceImpl）、`tool.kb_search`（BizTools，从 TenantInfo 取租户）
- 查询：`GET /api/audit`（按当前租户）
- 验证：对话后查询到 `agent.chat`（cost=2336ms）+ `tool.kb_search`（cost=8ms）两条留痕

**token 真实采集（已验证）：**
- `Msg.getUsage()` → `ChatUsage.getInputTokens()/getOutputTokens()`
- `ChatResult` 返回真实 token；上报 `llmCall` 事件；chat 接口 metrics 暴露 tokenIn/tokenOut
- 验证：审计显示 `tok=612/108`；评估 avgTokenTotal 由 0 占位改为真实值

**JWT 认证 + RBAC 拦截（已验证）：**
- `JwtUtil`：jjwt HS 签名签发/解析（subject=userId，claims: tenantId/role）
- `AuthController`：`POST /api/auth/login` 签发 token（demo 账号 admin/employee）
- `AuthInterceptor`：解析 `Authorization: Bearer` → 校验路径所需权限（AuthService）→ 注入 TenantContext；无 token 回退 header（demo），生产强制
- 路径→权限映射：`/api/agent`→`agent:chat`、`/api/eval`→`eval:run`、`/api/audit`→`audit:view`
- 验证：admin 登录→chat 200；employee 调 eval→**403**；非法 token→**401**

**代码结构：**
```
com.agent.biz.auth
├── JwtUtil.java          # JWT 签发/解析
├── AuthController.java   # 登录签发
├── AuthInterceptor.java  # 认证+RBAC 拦截（替代 TenantInterceptor）
├── AuthService.java      # 角色权限（含 audit:view）
└── com.agent.biz.audit
    ├── AuditLogger.java  # 审计日志（日志+内存缓冲）
    └── AuditController.java # 审计查询
```
