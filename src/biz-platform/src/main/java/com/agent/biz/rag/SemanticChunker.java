package com.agent.biz.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义分块器：段落感知，优于纯固定窗口。
 *
 * 策略：
 *  1. 按段落（空行）切分；
 *  2. 短段落合并到接近目标窗口，避免切碎语义；
 *  3. 超长段落按句子边界二次切分；
 *  4. 保留 overlap 维持跨块上下文。
 *
 * 对应 docs/03_模块设计/03_02_rag_module.md「文档分块策略」。
 * 相比 {@link Chunker}（纯固定窗口），更适合长文档/条款型文档。
 */
@Component
public class SemanticChunker {

    private final int chunkSize;
    private final int overlap;
    private static final String SENTENCE_END = "。！？!?；;\\n";

    public SemanticChunker(@Value("${rag.chunk-size:500}") int chunkSize,
                           @Value("${rag.chunk-overlap:80}") int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public List<Chunk> split(String text, long docId) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        String[] paras = text.split("\\R{2,}|\\n{2,}");
        List<String> merged = mergeParagraphs(paras);
        int index = 0;
        for (String para : merged) {
            for (String piece : splitLong(para)) {
                String content = piece.trim();
                if (!content.isEmpty()) {
                    chunks.add(new Chunk(docId, index++, content));
                }
            }
        }
        return chunks;
    }

    /** 合并短段落，避免切碎语义。 */
    private List<String> mergeParagraphs(String[] paras) {
        List<String> merged = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String p : paras) {
            String t = p.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (buf.length() == 0) {
                buf.append(t);
            } else if (buf.length() + t.length() <= chunkSize) {
                buf.append('\n').append(t);
            } else {
                merged.add(buf.toString());
                buf.setLength(0);
                buf.append(t);
            }
        }
        if (buf.length() > 0) {
            merged.add(buf.toString());
        }
        return merged;
    }

    /** 超长段落按句子边界 + 固定窗口二次切分（带 overlap）。 */
    private List<String> splitLong(String para) {
        List<String> out = new ArrayList<>();
        if (para.length() <= chunkSize) {
            out.add(para);
            return out;
        }
        int start = 0;
        while (start < para.length()) {
            int end = Math.min(start + chunkSize, para.length());
            // 尽量在句子边界断
            if (end < para.length()) {
                int cut = lastSentenceEnd(para, start, end);
                if (cut > start) {
                    end = cut;
                }
            }
            out.add(para.substring(start, end));
            if (end == para.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return out;
    }

    private int lastSentenceEnd(String text, int from, int to) {
        for (int i = to; i > from + 1; i--) {
            if (SENTENCE_END.indexOf(text.charAt(i - 1)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /** 分块结果对象（与 {@link Chunker.Chunk} 同构）。 */
    public record Chunk(long docId, int chunkIndex, String content) {
    }
}
