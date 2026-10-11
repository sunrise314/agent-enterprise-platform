package com.agent.biz.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 分块器：固定窗口 + overlap。
 *
 * 对应 docs/03_模块设计/03_02_rag_module.md
 * 企业 RAG 最容易踩坑的环节之一：分块过大召回精度差、token 浪费；过小语义断裂。
 */
public class Chunker {

    private final int chunkSize;
    private final int overlap;

    public Chunker(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    /**
     * 按固定窗口分块。
     *
     * @param text   原始文本
     * @param docId  文档 ID
     * @return 分块列表
     */
    public List<Chunk> split(String text, long docId) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        int index = 0;
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            String content = text.substring(start, end).trim();
            if (!content.isEmpty()) {
                chunks.add(new Chunk(docId, index++, content));
            }
            if (end == text.length()) {
                break;
            }
            start = end - overlap;
        }
        return chunks;
    }

    /** 分块结果对象。 */
    public record Chunk(long docId, int chunkIndex, String content) {
    }
}
