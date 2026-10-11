package com.agent.biz.rag;

/**
 * 向量化服务抽象：把文本转为 embedding 向量。
 *
 * 生产实现：{@link OpenAiEmbeddingService}（OpenAI 兼容 /embeddings 接口）。
 * 私有化部署可换 BGE-M3 / 通义 embedding 等，仅需实现本接口。
 */
public interface EmbeddingService {

    /** 把文本转为稠密向量。 */
    float[] embed(String text);

    /** 向量维度（建 collection 时使用）。 */
    int dimension();
}
