package com.agent.biz.rag;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 生产检索器：向量检索(Milvus) + BM25(内存简化) + RRF 重排，按租户隔离。
 *
 * 对应 docs/03_模块设计/03_02_rag_module.md「多路检索 + RRF 重排」。
 * 仅在 prod-milvus profile 下激活；demo 环境用 {@link InMemoryRetriever}。
 *
 * 部署要求：本机/容器需有 Milvus standalone（docker-compose 见 scripts/docker），
 * 并配置 embedding 服务（{@link OpenAiEmbeddingService} 指向本地 BGE 或 API）。
 */
@Profile({"prod-milvus", "milvus"})
@Component
public class MilvusHybridRetriever implements Retriever {

    private static final int RRF_K = 60;
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final MilvusClientV2 client;
    private final EmbeddingService embedding;
    private final int topKDefault;

    /** 已入库文档文本（用于关键词 BM25），与向量库并存。 */
    private final ConcurrentHashMap<String, KnowledgeDocument> docIndex = new ConcurrentHashMap<>();

    public MilvusHybridRetriever(
            EmbeddingService embedding,
            @Value("${rag.milvus.host:localhost}") String host,
            @Value("${rag.milvus.port:19530}") int port,
            @Value("${rag.milvus.topk:5}") int topKDefault) {
        this.embedding = embedding;
        this.topKDefault = topKDefault;
        ConnectConfig config = ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .build();
        this.client = new MilvusClientV2(config);
    }

    @Override
    public List<KnowledgeDocument> retrieve(String query, int topK, String tenantId) {
        String tenant = tenantId == null ? "tenant-0" : tenantId;
        int k = topK <= 0 ? topKDefault : topK;

        List<Ranked> vec = vectorSearch(query, k, tenant);
        List<Ranked> kw = keywordSearch(query, k, tenant);

        // RRF 融合：score = Σ 1/(RRF_K + rank)
        return merge(vec, kw, k);
    }

    @Override
    public void index(KnowledgeDocument doc) {
        String collection = collectionName(doc.tenantId());
        ensureCollection(collection);
        docIndex.put(doc.id(), doc);

        float[] vec = embedding.embed(doc.title() + " " + doc.content());
        JsonArray arr = new JsonArray();
        for (float f : vec) {
            arr.add(f);
        }
        JsonObject row = new JsonObject();
        row.addProperty("id", doc.id());
        row.addProperty("text", doc.title() + " " + doc.content());
        row.add("embedding", arr);

        InsertReq req = InsertReq.builder()
                .collectionName(collection)
                .data(List.of(row))
                .build();
        client.insert(req);
    }

    // ---------- 向量检索（Milvus） ----------
    private List<Ranked> vectorSearch(String query, int topK, String tenant) {
        String collection = collectionName(tenant);
        if (!hasCollection(collection)) {
            return List.of();
        }
        float[] qv = embedding.embed(query);
        FloatVec queryVec = new FloatVec(boxed(qv));
        SearchReq req = SearchReq.builder()
                .collectionName(collection)
                .annsField("embedding")
                .topK(topK)
                .data(List.of(queryVec))
                .outputFields(List.of("id", "text"))
                .build();
        SearchResp resp = client.search(req);
        if (resp == null || resp.getSearchResults() == null
                || resp.getSearchResults().isEmpty()) {
            return List.of();
        }
        List<Ranked> out = new ArrayList<>();
        int rank = 1;
        for (SearchResp.SearchResult r : resp.getSearchResults().get(0)) {
            Object id = r.getId();
            KnowledgeDocument doc = id == null ? null : docIndex.get(String.valueOf(id));
            if (doc != null) {
                out.add(new Ranked(doc, rank++));
            }
        }
        return out;
    }

    // ---------- 关键词检索（内存 BM25 简化，生产可换 OpenSearch） ----------
    private List<Ranked> keywordSearch(String query, int topK, String tenant) {
        Set<String> qTerms = terms(query);
        if (qTerms.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<KnowledgeDocument, Integer>> scored = new ArrayList<>();
        for (KnowledgeDocument doc : docIndex.values()) {
            if (!Objects.equals(tenant, doc.tenantId())) {
                continue;
            }
            Set<String> dTerms = terms(doc.title() + " " + doc.content());
            int overlap = (int) qTerms.stream().filter(dTerms::contains).count();
            if (overlap > 0) {
                scored.add(Map.entry(doc, overlap));
            }
        }
        scored.sort(Map.Entry.<KnowledgeDocument, Integer>comparingByValue(Comparator.reverseOrder()));
        List<Ranked> out = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<KnowledgeDocument, Integer> e : scored) {
            out.add(new Ranked(e.getKey(), rank++));
            if (rank > topK) {
                break;
            }
        }
        return out;
    }

    // ---------- RRF ----------
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
                .limit(Math.max(1, topK))
                .map(e -> docIndex.get(e.getKey()))
                .filter(Objects::nonNull)
                .toList();
    }

    // ---------- Milvus collection ----------
    private String collectionName(String tenant) {
        return "kb_" + tenant.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private boolean hasCollection(String name) {
        try {
            Boolean exists = client.hasCollection(HasCollectionReq.builder()
                    .collectionName(name).build());
            return exists != null && exists;
        } catch (Exception e) {
            return false;
        }
    }

    private synchronized void ensureCollection(String name) {
        if (hasCollection(name)) {
            return;
        }
        CreateCollectionReq req = CreateCollectionReq.builder()
                .collectionName(name)
                .dimension(embedding.dimension())
                .primaryFieldName("id")
                .idType(DataType.VarChar)
                .maxLength(256)
                .vectorFieldName("embedding")
                .metricType("COSINE")
                .enableDynamicField(true)
                .build();
        client.createCollection(req);
        client.loadCollection(LoadCollectionReq.builder().collectionName(name).build());
    }

    // ---------- util ----------
    private List<Float> boxed(float[] arr) {
        List<Float> out = new ArrayList<>(arr.length);
        for (float f : arr) {
            out.add(f);
        }
        return out;
    }

    private static Set<String> terms(String text) {
        String cleaned = text == null ? "" : text.toLowerCase(Locale.ROOT);
        Set<String> feats = new java.util.HashSet<>();
        TOKEN_SPLIT.splitAsStream(cleaned).filter(t -> t.length() > 1).forEach(feats::add);
        String compact = cleaned.replaceAll("\\s+", "");
        for (int i = 0; i + 1 < compact.length(); i++) {
            feats.add(compact.substring(i, i + 2));
        }
        return feats;
    }

    private record Ranked(KnowledgeDocument doc, int rank) {
    }
}
