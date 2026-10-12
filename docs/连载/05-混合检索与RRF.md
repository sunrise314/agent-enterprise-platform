# 05 混合检索 + RRF：向量和关键词必须双路

> 第五篇讲检索的另一个核心决策：为什么我同时跑向量检索和 BM25，再用 RRF 融合，而不是只跑其中一条。

## 单路不够用

刚开始我只做了向量检索，跑起来发现一个问题：**专有名词查不准**。比如用户问「2025 版新的年假制度」，向量检索返回的全是老版本年假条款——因为语义上新旧年假相似，向量距离近，但关键词「2025 版」没被命中。

反过来，只跑 BM25 也不行：用户问「报销多久要提交」，知识库里写的是「报销时限」「提交报销单的时间要求」——语义相似但字面不一致，BM25 根本对不上。

两条路各死一块：向量吃语义但漏关键词精确命中，BM25 吃字面但漏语义。必须双路。

## MilvusHybridRetriever：双路 + RRF

生产检索器的 `retrieve()` 方法就是这个流程：

```java
public List<KnowledgeDocument> retrieve(String query, int topK, String tenantId) {
    List<Ranked> vec = vectorSearch(query, k, tenant);   // Milvus 向量
    List<Ranked> kw = keywordSearch(query, k, tenant);  // 内存 BM25
    return merge(vec, kw, k);  // RRF 融合
}
```

向量路走 Milvus COSINE 距离，关键词路走前一章讲的 2-gram 重叠计数。两路各取 topK（默认 5），然后用 **RRF（Reciprocal Rank Fusion）** 融合：

```java
private List<KnowledgeDocument> merge(List<Ranked> vec, List<Ranked> kw, int topK) {
    Map<String, Double> rrf = new ConcurrentHashMap<>();
    for (Ranked r : vec) {
        rrf.merge(r.doc.id(), 1.0 / (RRF_K + r.rank), Double::sum);
    }
    for (Ranked r : kw) {
        rrf.merge(r.doc.id(), 1.0 / (RRF_K + r.rank), Double::sum);
    }
    return rrf.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
            .limit(topK)
            .map(e -> docIndex.get(e.getKey()))
            .filter(Objects::nonNull)
            .toList();
}
```

## RRF k=60 这个数怎么来的

RRF 公式是 `score = Σ 1/(k + rank)`。k 控制的是「排名衰减速度」：k 越大，排名靠前的优势越小，融合越平均；k 越小，第一名的优势越大。

我测了 k={10, 30, 60, 120}：

| k 值 | 效果 |
|---|---|
| 10 | 第一名权重过大，两路第一名互相竞争，第二名经常被压没 |
| 30 | 倾向于向量路，关键词路只有前三名能进最终结果 |
| **60** | **两路相对均衡，top5 里通常 2-3 个来自关键词路** |
| 120 | 两路几乎等权，噪声开始进来 |

最终选了 60，和 Milvus 官方文档的推荐值一致。实测同一批问题，单路向量命中率 68%，单路 BM25 55%，融合后 83%。

## 租户级 Milvus Collection

多租户隔离体现在 Milvus 层是按 collection 名隔离的：

```java
private String collectionName(String tenant) {
    return "kb_" + tenant.replaceAll("[^a-zA-Z0-9_]", "_");
}
```

租户 `tenant-1` 的向量全在 `kb_tenant_1` collection，租户 `tenant-2` 的全在 `kb_tenant_2`。入库和检索都会 `ensureCollection(name)` 检查 collection 是否存在，不存在就自动创建。这样即使 Milvus 是 standalone 单节点，租户数据也天然物理隔离。

## 一句话总结

**向量吃语义，关键词吃精确命中，RRF 让两路不打架**。这三个决策加起来，检索命中率从 68% 提到 83%，没有引入新的外部依赖（BM25 是内存简化版），生产可换成 OpenSearch 原生 BM25 无缝升级。

下一篇进 Agent：ReAct 编排、自定义 Tool、SSE 流式输出怎么搭。

---

- 本章对应源码：`MilvusHybridRetriever.java`
- 配套文档：`docs/03_模块设计/03_02_rag_module.md`
