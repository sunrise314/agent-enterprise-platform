package com.agent.biz.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 检索服务：封装文档检索，供 Agent 工具 kb_search 调用。
 *
 * 对应 docs/03_模块设计/03_02_rag_module.md「应用层 RAG」。
 * 采用 AgentScope 官方 ApplicationLayerRagExample 的应用层检索模式：
 * 检索 → 组装带来源的上下文 → 供 Agent 作答。
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final Retriever retriever;
    private final QueryRewriter queryRewriter;

    public RagService(Retriever retriever, QueryRewriter queryRewriter) {
        this.retriever = retriever;
        this.queryRewriter = queryRewriter;
    }

    /**
     * 检索指定租户的知识库，返回带来源标注的命中片段（供 Agent 工具 kb_search 消费）。
     *
     * @param query    检索问题
     * @param topK     返回片段数
     * @param tenantId 租户 ID（多租户隔离，必传）
     * @return 命中片段文本（[标题] + 正文，来源可溯源）
     */
    public String searchSummary(String query, int topK, String tenantId) {
        // Query 改写：口语问题 → 检索友好 query，再检索
        String rewritten = queryRewriter.rewrite(query);
        if (!rewritten.equals(query)) {
            log.info("query-rewrite: [{}] -> [{}]", query, rewritten);
        }
        List<KnowledgeDocument> hits = retriever.retrieve(rewritten, topK, tenantId);
        if (hits.isEmpty()) {
            return "(未检索到相关文档，请如实告知用户知识库中暂无此信息。)";
        }
        return hits.stream()
                .map(doc -> "【" + doc.title() + "】" + doc.content())
                .collect(Collectors.joining("\n\n"));
    }

    /** 入库一条文档（供后续文档 ingestion 接口调用）。 */
    public void indexDocument(KnowledgeDocument doc) {
        retriever.index(doc);
    }

    /** 返回底层检索器（供 ingestion 增量删除等高级操作）。 */
    public Retriever getRetriever() {
        return retriever;
    }
}
