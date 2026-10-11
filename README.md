# Agent 企业智能体平台（基于 AgentScope-Java 二次开发）

> 底层底座：AgentScope-Java（阿里开源，Apache 2.0）
> 上层业务应用：自研闭源业务平台 `biz-platform`，面向企业私有化交付，可交付源码给客户。

## 项目简介

本项目基于阿里开源 **AgentScope-Java**（JVM 原生 Agent 框架）二次开发，构建商用级企业智能体平台。

核心能力：
- 多租户 RAG 知识库（文档入库、分块、混合检索、RRF 重排）
- ReAct / Harness 智能体编排，支持自定义业务工具
- MCP 工具协议接入
- Agent 量化评估平台（任务完成率、工具调用准确率、幻觉率、token、延迟）
- RBAC 权限、多租户隔离、审计日志
- 可观测监控（链路追踪、指标采集、Grafana 看板）
- 内网私有化部署，支持国产大模型 / 国产向量库 / 国产数据库适配

> 开发原则：
> 1. 尽量不侵入 `agentscope-java` 底层源码，优先使用 SPI、事件监听、自定义 Tool 扩展；
> 2. 底座升级时可直接拉取上游版本合并，降低维护成本；
> 3. 所有业务增值模块（RAG、评估、权限、管理 API）全部放在 `biz-platform` 自研模块。

## 技术栈

| 类别 | 选型 |
|---|---|
| 语言 / 运行时 | Java 17 |
| 框架 | SpringBoot 3.x、AgentScope-Java、Project Reactor |
| Agent 编排 | HarnessAgent / ReActAgent、MCP、A2A |
| 会话存储 | Redis |
| 业务数据库 | PostgreSQL（业务数据、评估指标、测试用例） |
| 向量库 | Milvus |
| 关键词检索 | OpenSearch (BM25) |
| 可观测 | Micrometer + Prometheus + Grafana |
| 部署 | Docker / Docker Compose |

## 目录结构

```
agent-enterprise-platform/
├── README.md
├── docs/                          # 全套设计文档（Markdown，可迭代）
│   ├── 01_项目概述.md
│   ├── 02_系统架构.md
│   ├── 03_模块设计/
│   │   ├── 03_01_agent_core.md
│   │   ├── 03_02_rag_module.md
│   │   ├── 03_03_biz_tools.md
│   │   ├── 03_04_evaluation.md    # 【面试重点】Agent 量化评估
│   │   ├── 03_05_auth_rbac.md
│   │   ├── 03_06_observability.md
│   │   └── 03_07_gateway.md
│   ├── 04_数据模型.md
│   ├── 05_接口文档.md
│   ├── 06_部署运维/
│   ├── 07_开源合规.md
│   ├── 08_风险与坑点.md
│   └── 09_迭代规划.md
│   └── 10_面试备考.md               # 【面试】口语版 + 六层认知 + 验证证据链
│   └── 11_量化能力清单.md           # 【面试】16 项能力量化验证矩阵（复现动作+指标+证据）
├── src/
│   ├── agentscope-java/           # 上游 AgentScope-Java 源码（自行 clone）
│   └── biz-platform/              # 自研 SpringBoot 业务平台
├── scripts/                       # 评估脚本、docker 部署脚本
└── testcases/                     # 评估测试集
```

## 快速启动

1. 拉取上游 AgentScope-Java 源码到 `src/agentscope-java`：

```bash
cd src
git clone https://github.com/agentscope-ai/agentscope-java.git
```

2. 编译底座（本地 install 供 biz-platform 依赖）：

```bash
cd src/agentscope-java
mvn clean install -DskipTests
```

3. 启动 biz-platform 业务服务：

```bash
cd src/biz-platform
mvn spring-boot:run
```

## 开源合规声明

- 底层框架 AgentScope-Java：Apache 2.0 License。
- 本项目自研业务层代码（`biz-platform`）为闭源自研代码。
- 交付源码给第三方企业时，必须保留原项目版权声明、NOTICE 文件、Apache2.0 协议文本，并在修改过的文件头标注“已被修改”。

## 版本规划

- **v1.0**：最小商用交付版本（当前迭代目标）
- **v1.1**：增强多 Agent 协作、HITL 人机介入
- **v1.2**：K8s 生产部署、高可用集群

## 面试说明

本项目可用于 AI 应用开发工程师面试深度考察，覆盖：
Agent 编排、RAG、量化评估、工程稳定性、开源合规、私有化交付等核心考点。
