package com.agent.biz.rag;

import java.util.List;

/**
 * 检索器抽象：支持文档入库与检索。
 *
 * 实现：
 *  - {@link InMemoryRetriever}：demo（BM25 简化关键词检索，无外部依赖，可真实召回）
 *  - MilvusHybridRetriever：生产（向量检索 Milvus + BM25 + RRF 重排，需 Milvus/OpenSearch 环境）
 *
 * 对应 docs/03_模块设计/03_02_rag_module.md「多路检索」
 */
public interface Retriever {

    /** 按 query 检索指定租户的文档，返回相关文档（按相关度降序）。租户隔离由调用方显式传入。 */
    List<KnowledgeDocument> retrieve(String query, int topK, String tenantId);

    /** 入库一条文档。 */
    void index(KnowledgeDocument doc);

    /** 按标题删除指定租户的文档（增量更新用）。 */
    default void removeByTitle(String title, String tenantId) {
        // 默认无操作；支持增量的实现需覆盖
    }
}
