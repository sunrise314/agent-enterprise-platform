# 03_02 RAG 知识库模块

> 企业文档智能问答的核心，负责从文档入库到检索召回、重排的完整链路。

## 3.2.1 模块职责

- 文档解析（PDF / Word / TXT，支持表格）
- 分块策略（固定分块 + 语义分块）
- 向量化入库（Milvus）
- 多路检索（向量检索 + BM25 关键词检索）+ RRF 重排
- 知识库隔离（不同租户 / 用户只能检索自己权限内文档）

## 3.2.2 完整链路（Document Ingestion → Retrieval）

```
文档上传
  → 解析（PDF/Word，提取文本、表格、元数据）
  → 清洗（去页眉页脚、去噪音）
  → 分块（固定 chunk 或语义切分）
  → 向量化（Embedding 模型）
  → 向量 + 原文入库（Milvus + PostgreSQL）

用户提问
  → Query 改写（可选，指令改写 / 扩写）
  → 多路检索：
      ① 向量检索（Milvus，语义相似）
      ② 关键词检索（BM25，精确匹配）
  → RRF 重排（融合两路结果，加权排序）
  → Top-K 命中片段
  → 注入 Prompt，LLM 生成答案
```

## 3.2.3 关键组件

| 组件 | 选型 / 实现 | 说明 |
|---|---|---|
| 文档解析 | Apache PDFBox + POI | PDF / Word / 表格 |
| 分块 | 自研 Chunker | 固定窗口 + 语义边界检测 |
| Embedding | 通义 / BGE-M3 | 私有化部署优先 |
| 向量库 | Milvus | 向量检索 |
| 关键词检索 | OpenSearch (BM25) | 精确召回 |
| 重排 | RRF（Reciprocal Rank Fusion） | 融合多路结果 |
| Query 改写 | LLM 调用 | 可选增强 |

## 3.2.4 分块策略（面试重点）

**固定分块：**
- 优点：简单、可复现
- 缺点：可能切断语义、导致召回碎片化
- 常用参数：chunk_size=500，overlap=50

**语义分块：**
- 按段落 / 语义边界切分，保留完整语义单元
- 缺点：chunk 大小不一，向量化成本略高

**实践结论：**
- 结构文档（制度、合同）优先语义分块 + 保留标题层级
- 大文档用固定分块 + overlap 兜底
- 建索引时写入文档元数据（标题、来源、章节），供溯源引用

## 3.2.5 多路检索 + RRF 重排

- 向量检索擅长语义相近但字面不同的匹配
- BM25 擅长精确术语 / 编号匹配
- RRF 公式：`score(d) = Σ 1/(k + rank_i(d))`，k 通常取 60

## 3.2.6 知识库隔离

- 每个文档记录 `tenantId` + `kbId`
- 检索时强制附加租户过滤条件，防止跨租户数据泄露
- 向量库 Collection 按租户命名（可选）或统一 Collection + 过滤字段

## 3.2.7 核心代码结构

```
com.agent.biz.rag
├── DocumentParser.java      # 文档解析
├── TextCleaner.java         # 文本清洗
├── Chunker.java             # 分块
├── EmbeddingService.java    # 向量化
├── VectorStore.java         # Milvus 存取
├── HybridRetriever.java     # 多路检索 + RRF 重排
├── QueryRewriter.java       # Query 改写
└── RagService.java          # RAG 对外服务
```

## 3.2.8 面试要点（Q&A 速记）

- **Q：分块太大 / 太小会有什么问题？**
  答：太大导致召回精度下降、token 浪费；太小导致语义断裂、上下文不足。需要按文档类型调参。
- **Q：为什么用混合检索？**
  答：纯向量检索对精确术语（合同编号、专有名词）召回差；BM25 对语义表达缺失。两者互补，RRF 融合。
- **Q：怎么保证引用可溯源？**
  答：检索命中片段带来源元数据（文档标题、章节、页码），生成答案时要求 LLM 输出引用来源。

## 3.2.9 当前实现状态（已落地并验证）

**采用 AgentScope 官方 ApplicationLayerRagExample 的应用层 RAG 模式**：
检索在应用层完成，检索结果通过 `kb_search` 工具交给 Agent，Agent 基于检索上下文作答。

**已实现代码：**
```
com.agent.biz.rag
├── KnowledgeDocument.java   # 文档单元（id/title/content/tenantId）
├── Retriever.java           # 检索器接口（retrieve / index）
├── InMemoryRetriever.java   # demo 实现（bigram BM25 简化检索）
├── Chunker.java             # 分块（固定窗口 + overlap）
├── SemanticChunker.java     # 语义分块（段落感知 + 句子边界，生产 ingestion 用）
├── DocumentParser.java      # 文档解析（PDF / DOCX / TXT，PDFBox + POI）
├── KbController.java        # POST /api/kb/ingest（上传 → 解析 → 分块 → 入库）
└── RagService.java          # RAG 对外服务（searchSummary / indexDocument）
```

**InMemoryRetriever（demo，可真实召回）：**
- 预置企业知识文档（员工手册 / 报销制度 / 采购流程 / 信息安全）
- **bigram(2-gram) 特征匹配**解决中文分词缺失：`「年假政策」→{年假,假政,政策}`，文档 `「年假需提前」→{年假,假需,...}`，交集命中
- 英文单词 token + 中文 2-gram 混合特征，IDF 加权评分

**RagService.searchSummary(query, topK)**：检索 → 格式化为 `【标题】正文` 带来源文本，供 `kb_search` 工具消费。

**验证结果（demo/InMemory）：**
- 提问「公司年假政策？满几年休几天？」
- `kb_search` 真实调用，`result_len=173`，命中【员工手册】
- Agent 正确作答：满 1 年 5 天 / 满 10 年 10 天 / 满 20 年 15 天，提前 3 个工作日 OA 申请 ✅

**文档 ingestion 链路（已验证）：**
- `POST /api/kb/ingest`（multipart file + title 可选）→ `DocumentParser.parse` → `SemanticChunker.split` → 按当前租户入库
- 语义分块策略：按段落切 → 短段合并（避免切碎语义）→ 超长段按句子边界二次切（带 overlap）
- 验证：上传「外包制度.txt」→ indexed=1（短文档合并成 1 块）→ 提问"外包人员可以访问生产数据库吗？" → 检索召回新文档并准确作答 ✅

**关键坑点（面试重点）：**
- 中文无空格分词，纯 token 精确匹配必失效；用 2-gram 或引入分词器（HanLP/jieba）解决
- 工具 description 必须写清「何时用、参数含义」，否则 LLM 选错工具（召回准确率下降）
- 纯固定窗口会切碎条款型文档语义；语义分块（段落感知）对制度/合同类文档更稳

**Query 改写（已验证）：**
- `QueryRewriter`：LLM（DeepSeek）把口语问题改写为检索友好 query，失败兜底原 query
- 接入点：`RagService.searchSummary` 检索前置统一改写
- 验证（日志 `query-rewrite`）：「我们公司请假咋算的呀，能休几天？」→「请假流程 申请 审批」，改写后正确召回年假规定 ✅
- 设计要点：改写独立于检索，可在网关侧统一接入；生产可配缓存/仅对短 query 触发以控成本

**生产待办（需 Milvus/OpenSearch 环境）：**
- `HybridRetriever`：向量检索(Milvus) + BM25(OpenSearch) + RRF 重排
- `EmbeddingService` 向量化、知识库租户隔离（已就绪，见 3.2.10）
- 增量/增量更新 ingestion（当前每次全量解析重放）

## 3.2.11 增量 ingestion（已实现并验证）

**已实现：内容哈希去重 + 标题版本更新，避免重复入库。**

**代码：**
```
com.agent.biz.rag
├── DocVersionTracker.java    # 标题+租户 → 内容哈希跟踪（SHA-256）
└── Retriever.removeByTitle() # 删除指定标题的旧分块（增量更新）
```

**流程（POST /api/kb/ingest）：**
1. 解析全文 → SHA-256 内容哈希
2. 同「标题+租户」内容未变 → 跳过（`skipped=true, indexed=0`）
3. 内容变化 → `removeByTitle` 删旧块 → 重新分块入库（更新）

**验证结果（demo）：**
```
1) 首次上传   → indexed=1, skipped=false
2) 重复上传   → indexed=0, skipped=true（内容未变化，跳过重复入库）✅ 去重
3) 修改后上传 → indexed=1, skipped=false（更新）✅
```

**设计要点（面试）：**
- 避免重复文档占用检索空间、干扰相关度排序
- 内容哈希比标题比对更精确：标题不变内容微调也能识别变化
- 生产可用数据库版本字段 + 软删除持久化（demo 内存跟踪）

## 3.2.10 生产实现（Milvus 混合检索，已就绪，编译通过）
**生产检索器 `MilvusHybridRetriever` 已实现并编译通过**（`@Profile("prod-milvus")` 激活，demo 不受影响）。

**实现（Milvus Java SDK v2 API）：**
```
com.agent.biz.rag
├── EmbeddingService.java        # 向量化抽象（embed / dimension）
├── OpenAiEmbeddingService.java  # OpenAI 兼容 /embeddings（可指向本地 BGE/Xinference/Ollama）
└── MilvusHybridRetriever.java   # 生产检索：Milvus 向量 + BM25 + RRF，按租户隔离
```

**检索流程：**
```
query → embedding.embed(query) 向量化
  → ① Milvus 向量搜索（collection=kb_{tenantId}，COSINE，annsField=embedding）
  → ② 关键词检索（内存 BM25，生产可换 OpenSearch）
  → ③ RRF 融合（score = Σ 1/(60 + rank)）→ Top-K
```

**关键 API（已反编译 SDK 确认，非臆测）：**
- `ConnectConfig.builder().uri("http://host:port")` → `new MilvusClientV2(config)`
- `CreateCollectionReq.builder().collectionName().dimension().primaryFieldName("id").idType(DataType.VarChar).maxLength().vectorFieldName("embedding").metricType("COSINE")`
- `InsertReq.builder().collectionName().data(List<gson JsonObject>)`（embedding 字段为 Float 数组）
- `SearchReq.builder().collectionName().annsField().topK().data(List<FloatVec>).outputFields()`
- `SearchResp.getSearchResults().get(0)` → `SearchResult.getEntity()/getScore()/getId()`

**多租户**：collection 按租户命名 `kb_{tenantId}`，向量与关键词检索都按租户过滤。

**部署要求（实机验证前置条件）：**
- Milvus standalone（`docker compose up`，见 scripts/docker，需 Docker）
- embedding 服务：`rag.embedding.base-url/api-key/model` 配置（私有化指向本地 BGE 最佳）
- 启动 profile 设为 `prod-milvus`

### 3.2.12 Milvus 实机向量检索验证（已通过 ✅ 2026-10-11）

**环境（本机 Docker Desktop 全部本地自部署）：**
```
Milvus standalone   v2.4.12   容器 milvus-standalone/minio/etcd 三容器 Up(healthy)，gRPC 19530
Embedding           TEI(text-embeddings-inference) + BAAI/bge-small-zh-v1.5（512 维）
                    镜像 ghcr.m.daocloud.io（daocloud ghcr 代理加速）
                    模型文件由 ModelScope(魔搭) 下载到本机后挂载容器 /model 本地加载
启动 profile        milvus（独立验证 profile，H2 内存库 + InMemoryChatMemory + MilvusHybridRetriever + OpenAiEmbeddingService）
```

**embedding 服务网络坑（面试重点）：**
- 本机到 HuggingFace 直连 / hf-mirror 均被网络阻断（connection refused / content-range missing / 超时）
- TEI 在线下载模型反复失败 → 改为 **ModelScope（魔搭，国内）下载模型文件**到 `scripts/docker/tei-model/bge-small-zh-v1.5`，TEI 容器用 `--model-id /model` 本地路径加载，不再联网
- 结论：国内私有化部署 embedding 建议用 ModelScope 下载模型喂本地推理服务，或直接选国内 embedding 服务

**端到端验证（全链路真实数据）：**
```
1) POST /api/kb/ingest 上传《员工手册.txt》
   → SemanticChunker 语义分块 = 2 chunks，chunkSize=488
   → bge-small-zh-v1.5 embedding(512 维) → 插入 Milvus collection kb_tenant-0
   → 返回 {"indexed":2, "skipped":false, "tenantId":"tenant-0"} ✅

2) POST /api/agent/chat 提问「员工一年可以休几天年假？」
   → kb_search 向量检索从 Milvus 召回年假 chunk
   → Agent 基于检索上下文正确作答：
      · 入职满一年正式员工，自次年起每年享有 5 天带薪年假
      · 年假需在自然年内休完，不可跨年累计
      · 申请需提前三个工作日提交审批，避开项目关键交付节点 ✅
   → 指标：tokenIn=888 / tokenOut=89 / latency=2768ms
   → 审计：AUDIT action=agent.chat 同步记录
```

**实机验证暴露并修复的缺陷（本项目真实增强点）：**
- `OpenAiEmbeddingService` 调用 TEI `/embeddings` 时未显式设置 Content-Type，RestTemplate 发送导致 TEI 返回 415
  → 修复：`headers.setContentType(MediaType.APPLICATION_JSON)`（embedding HTTP 请求必须显式声明 JSON）
- `KbController` catch 吞异常不记录 → 修复：`log.error("kb ingest failed...", e)` 记录完整栈
- 应用残留进程锁住 target jar 导致 spring-boot repackage 无法 rename → 需先停 java 进程再打包（Windows 部署注意）

**当前 profile 注解矩阵（milvus 验证 profile）：**
| Bean | @Profile |
|---|---|
| InMemoryChatMemory | demo, milvus |
| RedisChatMemory | !demo & !milvus |
| InMemoryRetriever | demo |
| MilvusHybridRetriever | prod-milvus, milvus |
| OpenAiEmbeddingService | prod-milvus, milvus |
| InMemoryMetricsStore | demo, milvus |

