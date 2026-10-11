# 03_03 业务工具模块（Biz Tools）

> Agent 执行实际业务动作的通道，通过 AgentScope-Java Tool 接口注册进 Agent。

## 3.3.1 模块职责

- 提供 RAG 检索工具（对接 03_02）
- 提供业务 SQL 查询工具（只读沙箱）
- 提供文件导出工具（PDF / Markdown）
- 管理 MCP 工具注册
- 工具参数校验与安全控制

## 3.3.2 内置工具清单（v1.0）

| 工具名 | 功能 | 安全控制 |
|---|---|---|
| `kb_search` | 知识库检索 | 租户过滤 |
| `db_query` | 业务库只读查询 | SQL 沙箱，仅 SELECT，禁删改 |
| `export_file` | 导出 PDF / Markdown | 大小、类型白名单 |
| `get_time` | 获取当前时间 | 无 |
| `calc` | 数值计算 | 无 |

## 3.3.3 SQL 查询工具安全设计（重点）

- **只读强制**：仅允许 `SELECT`，拦截 `INSERT/UPDATE/DELETE/DDL`
- **语句白名单**：只允许单条语句，禁止 `;` 拼接多语句
- **超时控制**：查询超时（默认 3s）自动终止
- **行数限制**：强制 `LIMIT`，默认 50 行
- **敏感列脱敏**：对密码、手机号、身份证等字段脱敏
- **审计日志**：记录完整 SQL、执行人、租户、耗时

## 3.3.4 工具定义方式

AgentScope-Java 中自定义 Tool 实现 `Tool` 接口，声明：
- `name`：工具名
- `description`：工具用途描述（LLM 据此选择工具，描述要清晰）
- `parameters`：入参 schema（JSON Schema）
- `execute()`：执行逻辑

> 注意：工具 description 必须写清楚「什么时候用、参数含义」，LLM 选错工具大多是描述不清导致。

## 3.3.5 MCP 工具接入

- 通过 MCP 协议注册外部工具（数据库、文件、第三方系统）
- 统一工具注册表管理，可动态启用 / 停用
- 工具权限与租户隔离

## 3.3.6 核心代码结构

```
com.agent.biz.tool
├── KbSearchTool.java      # 知识库检索工具
├── DbQueryTool.java       # 只读 SQL 查询工具
├── ExportFileTool.java    # 文件导出工具
├── ToolRegistry.java      # 工具注册表
├── SqlGuard.java          # SQL 安全守卫
└── McpToolManager.java    # MCP 工具管理
```

## 3.3.7 面试要点（Q&A 速记）

- **Q：工具参数写错 / 选错工具怎么处理？**
  答：工具 description 尽量明确；Agent 框架支持工具调用结果回传，参数错误时返回错误信息让 LLM 自纠；同时统计工具调用准确率评估效果。
- **Q：SQL 工具怎么防注入 / 防删改？**
  答：只读白名单 + 单语句限制 + 超时 + LIMIT + 敏感字段脱敏 + 审计日志。
- **Q：工具与 RAG 什么关系？**
  答：RAG 检索也是 Agent 的一个工具（kb_search），Agent 自主判断是否需要检索、查库，实现多步骤任务编排。
