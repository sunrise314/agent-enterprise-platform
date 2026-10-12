# 03 RAG 入库链路：从 PDF 到可检索向量

> 第三篇进 RAG。这一章讲文档怎么从原始文件变成 Milvus 里的向量：解析、分块、去重、embedding、入库，每一步的取舍和实测数据。

## 全链路概览

```
PDF / Word / TXT  →  DocumentParser  →  SemanticChunker  →  EmbeddingService  →  Milvus
                                          + SHA-256 增量去重           + BM25 关键词索引
```

整个入口是 `RagService.indexDocument()`，它把一个 `KnowledgeDocument` 交给底层 `Retriever.index()`。`Retriever` 接口有两个实现：demo 态下是 `InMemoryRetriever`（纯内存，方便笔记本演示），milvus 态下是 `MilvusHybridRetriever`（向量 + 关键词双路）。Profile 切换逻辑前一章讲过，这章只讲生产路径。

## 解析：DocumentParser

支持三种格式：PDF（用 PDFBox）、Word（用 poi-tl）、纯文本。PDF 是坑最多的格式——扫描件没有文本层、表格被拆成零散字符串、页眉页脚重复出现。我的处理策略很朴素：

1. **能拿到文本层就直接拿**；
2. 拿不到就返回解析失败，不做 OCR（私有化部署不想装 Tesseract 一类额外依赖）；
3. 页眉页脚重复：靠后面的 SHA-256 去重兜底。

实测：项目自带的 `员工手册.txt`（纯文本，约 47KB）解析 12ms；如果换成 PDF 扫描件，直接返回空，由上层提示用户「该文件无可解析文本，请上传 Word/TXT 版本」。

## 分块：为什么不用固定窗口，用语义分块

最开始我用的是纯固定窗口：500 字一切，overlap 80。结果发现切出来的块经常把一个条款的标题和内容切碎，检索命中率很差。

后来换成 `SemanticChunker`，策略是四段式：

1. **按段落切分**：空行分隔，尊重文档天然结构；
2. **短段落合并**：短于 500 字的段落往上拼，避免切碎语义；
3. **超长段落二次切分**：在句子边界（。！？）断，带 overlap；
4. **overlap=80**：跨块保留上下文。

代码里的核心逻辑：

```java
// SemanticChunker.java
String[] paras = text.split("\\R{2,}|\\n{2,}");  // 按空行切
List<String> merged = mergeParagraphs(paras);     // 短段落合并
// 超长段落按句子边界 + overlap 二次切分
while (start < para.length()) {
    int end = Math.min(start + chunkSize, para.length());
    int cut = lastSentenceEnd(para, start, end); // 尽量在句号断
    start = Math.max(end - overlap, start + 1);   // overlap
}
```

实测：`员工手册.txt` 切出 47 个块，平均块长 482 字；固定窗口切出 41 个块但其中 8 个跨了条款标题和正文。用同一批问题对比检索命中率，语义分块比固定窗口高 23%。

## 去重：SHA-256 增量入库

文档经常有「重复上传同一文件」或「上传了更新版本」的情况。我在入库前对每个 chunk 的文本算 SHA-256：

- 已存在相同 hash 的 chunk → 跳过；
- 不存在 → 入库。

这保证了同一文档多次上传不会产生重复向量。同时我还维护了 `DocVersionTracker`，记录每个文档的版本号和 chunk 列表，删旧版时能精准定位要删哪些向量。

## Embedding：两条腿走路

`EmbeddingService` 接口有两个实现：

| 实现 | 场景 | 向量模型 |
|---|---|---|
| `OpenAiEmbeddingService` | API 模式 | 任何 OpenAI-compatible 接口（DeepSeek、Ollama 等） |
| （本地 TEI，需单独下载 bge-small-zh-v1.5 模型） | 私有化 | BGE 小模型 |

向量维度 512（BGE-small-zh），Milvus collection 创建时显式指定 `metricType="COSINE"`。

## 一句话总结

入库链路的核心设计点：**语义分块 + SHA-256 去重 + 双路 Embedding**。实测数据：47KB 员工手册 → 47 个 chunk → Milvus + 内存 BM25 双路入库耗时 1.2 秒，增量更新时跳过重复 chunk 耗时降到 0.3 秒。

下一篇进检索的核心坑：中文无分词怎么办。

---

- 本章对应源码：`SemanticChunker.java`、`RagService.java`、`DocumentParser.java`
- 配套文档：`docs/03_模块设计/03_02_rag_module.md`
