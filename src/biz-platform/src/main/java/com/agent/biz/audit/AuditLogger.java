package com.agent.biz.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 审计日志器：记录 Agent 调用、工具调用等关键操作，支持查询。
 *
 * 商用合规硬需求：只追加、带操作人（租户+用户）、时间、结果，可追溯。
 * 当前落点：日志 + 内存环形缓冲（可查询）；生产升级为落库(PostgreSQL)/ES，支持导出审计。
 */
@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger("AUDIT");
    private static final int MAX_BUFFER = 2000;

    private final ConcurrentLinkedQueue<AuditEntry> buffer = new ConcurrentLinkedQueue<>();

    public void record(String tenantId, Long userId, String action, String target,
                       String detail, boolean success, long costMs) {
        AuditEntry e = new AuditEntry(Instant.now(), tenantId, userId, action, target,
                detail, success, costMs);
        buffer.add(e);
        while (buffer.size() > MAX_BUFFER) {
            buffer.poll();
        }
        log.info("AUDIT tenant={} user={} action={} target={} success={} cost={}ms detail={}",
                tenantId, userId, action, target, success, costMs,
                detail == null ? "" : detail);
    }

    /** 按租户查询最近审计记录（生产应改为查库）。 */
    public List<AuditEntry> query(String tenantId, int limit) {
        List<AuditEntry> out = new ArrayList<>();
        for (AuditEntry e : buffer) {
            if (tenantId == null || tenantId.equals(e.tenantId())) {
                out.add(e);
            }
        }
        int size = out.size();
        if (size > limit) {
            out = new ArrayList<>(out.subList(size - limit, size));
        }
        return out;
    }

    /** 一条审计记录。 */
    public record AuditEntry(
            Instant ts,
            String tenantId,
            Long userId,
            String action,
            String target,
            String detail,
            boolean success,
            long costMs) {
    }
}
