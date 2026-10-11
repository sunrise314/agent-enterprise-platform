package com.agent.biz.rag;

import com.agent.biz.auth.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库 ingestion 接口：上传文档 → 解析 → 语义分块 → 入库（按当前租户）。
 *
 * 对应 docs/03_模块设计/03_02_rag_module.md「文档 ingestion 链路」。
 * 设计：
 *  - 文档解析（PDF/Word/TXT）由 {@link DocumentParser} 负责
 *  - 语义分块由 {@link SemanticChunker} 负责（段落感知）
 *  - 分块写入当前租户（多租户隔离）
 */
@RestController
@RequestMapping("/api/kb")
public class KbController {

    private static final Logger log = LoggerFactory.getLogger(KbController.class);

    private final DocumentParser parser;
    private final SemanticChunker chunker;
    private final RagService ragService;
    private final DocVersionTracker versionTracker;

    public KbController(DocumentParser parser, SemanticChunker chunker, RagService ragService,
                        DocVersionTracker versionTracker) {
        this.parser = parser;
        this.chunker = chunker;
        this.ragService = ragService;
        this.versionTracker = versionTracker;
    }

    /** 上传文档入库。body: multipart/form-data（file + title 可选）。 */
    @PostMapping("/ingest")
    public Map<String, Object> ingest(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title) {
        String docTitle = "unknown";
        try {
            String name = file.getOriginalFilename();
            docTitle = title == null || title.isBlank()
                    ? stripExt(name)
                    : title;
            String text = parser.parse(file);
            String tenantId = TenantContext.tenantId();

            // 增量：内容哈希未变 → 跳过；变化 → 删除旧块 + 重新入库
            boolean changed = versionTracker.changed(docTitle, tenantId, text);
            if (!changed) {
                Map<String, Object> skip = new LinkedHashMap<>();
                skip.put("indexed", 0);
                skip.put("skipped", true);
                skip.put("title", docTitle);
                skip.put("tenantId", tenantId);
                skip.put("msg", "内容未变化，跳过重复入库");
                return Map.of("code", 0, "data", skip);
            }
            if (versionTracker.exists(docTitle, tenantId)) {
                ragService.getRetriever().removeByTitle(docTitle, tenantId);
            }

            long base = System.currentTimeMillis();
            List<SemanticChunker.Chunk> chunks = chunker.split(text, base);
            int indexed = 0;
            for (SemanticChunker.Chunk c : chunks) {
                ragService.indexDocument(new KnowledgeDocument(
                        "ingest-" + base + "-" + c.chunkIndex(), docTitle, c.content(), tenantId));
                indexed++;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("indexed", indexed);
            data.put("skipped", false);
            data.put("title", docTitle);
            data.put("tenantId", tenantId);
            data.put("chunkSize", chunks.isEmpty() ? 0 : chunks.get(0).content().length());
            return Map.of("code", 0, "data", data);
        } catch (Exception e) {
            log.error("kb ingest failed for {}", docTitle, e);
            return Map.of("code", 1, "msg", "ingest failed: " + e.getMessage());
        }
    }

    private String stripExt(String name) {
        if (name == null) {
            return "untitled";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
