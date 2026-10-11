package com.agent.biz.rag;

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
 * 内存检索器（demo，bigram BM25 简化关键词检索，无外部依赖，可真实召回）。
 *
 * 对应 docs/03_模块设计/03_02_rag_module.md 与 03_05_auth_rbac.md。
 * **多租户隔离**：检索按显式传入的 tenantId 过滤（由工具从 RuntimeContext 注入的
 * TenantInfo 取得，避免 ThreadLocal 跨线程丢失），防止跨租户数据泄露。
 */
@Profile("demo")
@Component
public class InMemoryRetriever implements Retriever {

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final ConcurrentHashMap<String, KnowledgeDocument> docs = new ConcurrentHashMap<>();
    private final List<KnowledgeDocument> ordered = new ArrayList<>();

    public InMemoryRetriever() {
        seedDocuments();
    }

    @Override
    public List<KnowledgeDocument> retrieve(String query, int topK, String tenantId) {
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        String tenant = tenantId == null ? "tenant-0" : tenantId;
        return ordered.stream()
                .filter(doc -> Objects.equals(tenant, doc.tenantId()))
                .map(doc -> Map.entry(doc, bm25Score(queryTerms, doc)))
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<KnowledgeDocument, Double>comparingByValue(
                                Comparator.reverseOrder())
                        .thenComparing(e -> e.getKey().id()))
                .limit(Math.max(1, topK))
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public void index(KnowledgeDocument doc) {
        if (doc != null && doc.id() != null && docs.putIfAbsent(doc.id(), doc) == null) {
            ordered.add(doc);
        }
    }

    @Override
    public synchronized void removeByTitle(String title, String tenantId) {
        List<String> toRemove = docs.values().stream()
                .filter(d -> title != null && title.equals(d.title())
                        && java.util.Objects.equals(tenantId, d.tenantId()))
                .map(KnowledgeDocument::id)
                .toList();
        for (String id : toRemove) {
            docs.remove(id);
            ordered.removeIf(d -> d.id().equals(id));
        }
    }

    /** 简化的 BM25 相关度：查询特征在文档中的命中权重。 */
    private double bm25Score(Set<String> queryTerms, KnowledgeDocument doc) {
        Set<String> docTerms = terms(doc.title() + " " + doc.content());
        int overlap = (int) queryTerms.stream().filter(docTerms::contains).count();
        if (overlap == 0) {
            return 0;
        }
        double idf = Math.log(1 + (double) ordered.size() / Math.max(1, docTerms.size()));
        return overlap * (1 + idf);
    }

    /**
     * 特征提取：单词 token + 相邻字符二元组（2-gram）。
     * 中文没有空格分词，必须用 2-gram 才能让「年假政策」与文档中的「年假需提前」产生交集。
     */
    private static Set<String> terms(String text) {
        String cleaned = text == null ? "" : text.toLowerCase(Locale.ROOT);
        Set<String> feats = new java.util.HashSet<>();
        TOKEN_SPLIT.splitAsStream(cleaned)
                .filter(t -> t.length() > 1)
                .forEach(feats::add);
        String compact = cleaned.replaceAll("\\s+", "");
        for (int i = 0; i + 1 < compact.length(); i++) {
            feats.add(compact.substring(i, i + 2));
        }
        return feats;
    }

    /** 预置多租户知识文档：tenant-0 为默认/评估租户，tenant-1 为隔离验证租户。 */
    private void seedDocuments() {
        index(new KnowledgeDocument("doc-employee-handbook",
                "员工手册",
                "正式员工每年享有 5-15 天带薪年假，工作满 1 年 5 天，满 10 年 10 天，满 20 年 15 天。"
                        + "年假需提前 3 个工作日通过 OA 系统提交申请。",
                "tenant-0"));
        index(new KnowledgeDocument("doc-reimbursement",
                "报销制度",
                "员工报销需在费用发生后 30 天内提交，附发票与审批单。差旅报销标准：一线城市住宿 500 元/晚，"
                        + "交通凭票实报实销。财务部在 7 个工作日内完成审核打款。",
                "tenant-0"));
        index(new KnowledgeDocument("doc-procurement",
                "采购审批流程",
                "采购金额 5000 元以下由部门经理审批；5000-50000 元需采购部复核并报总监；"
                        + "50000 元以上需提交总经理办公会审议。紧急采购需先口头报备。",
                "tenant-0"));
        index(new KnowledgeDocument("doc-it-security",
                "信息安全制度",
                "员工账号密码每 90 天必须更换一次，不得将办公设备或账号借予他人。"
                        + "生产数据加密存储，涉密文件需通过加密通道传输。",
                "tenant-0"));

        // tenant-1：独立知识库，用于验证租户隔离
        index(new KnowledgeDocument("doc-travel-policy",
                "出差政策",
                "tenant-1 员工出差住宿标准为一线城市 400 元/晚，出差补助按自然日 80 元/天发放，"
                        + "需出差前通过 OA 提交出差申请单。",
                "tenant-1"));
        index(new KnowledgeDocument("doc-overtime",
                "加班调休制度",
                "tenant-1 员工工作日加班满 2 小时可申请调休，加班时长按 1:1 折算调休，"
                        + "法定节假日加班按 3 倍工资计算。",
                "tenant-1"));
    }
}
