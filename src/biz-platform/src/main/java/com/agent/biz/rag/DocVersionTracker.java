package com.agent.biz.rag;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档版本跟踪器：按「标题 + 租户」记录内容哈希，实现增量 ingestion 去重与更新。
 *
 * 对应 docs/03_模块设计/03_02_rag_module.md「增量 ingestion」。
 *  - 内容哈希未变 → 跳过（重复上传）
 *  - 内容哈希变化 → 删除旧分块 + 重新入库（更新）
 * 生产可用数据库/版本字段持久化；demo 内存跟踪。
 */
@Component
public class DocVersionTracker {

    private final ConcurrentHashMap<String, String> versions = new ConcurrentHashMap<>();

    /** 若同标题内容未变则跳过；否则删除旧块并更新版本。 */
    public boolean changed(String title, String tenantId, String content) {
        String key = key(title, tenantId);
        String hash = sha256(content);
        String old = versions.get(key);
        if (hash.equals(old)) {
            return false; // 内容未变，跳过
        }
        versions.put(key, hash);
        return true;
    }

    public boolean exists(String title, String tenantId) {
        return versions.containsKey(key(title, tenantId));
    }

    private String key(String title, String tenantId) {
        return (title == null ? "" : title) + "::" + (tenantId == null ? "" : tenantId);
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return String.valueOf((text == null ? "" : text).hashCode());
        }
    }
}
