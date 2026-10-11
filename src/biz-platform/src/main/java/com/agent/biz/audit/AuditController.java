package com.agent.biz.audit;

import com.agent.biz.auth.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 审计查询接口：按当前租户拉取审计记录。
 *
 * 对应 docs/03_模块设计/03_05_auth_rbac.md「审计日志」。
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditLogger audit;

    public AuditController(AuditLogger audit) {
        this.audit = audit;
    }

    /** 查询当前租户的审计记录（默认最近 50 条）。 */
    @GetMapping
    public Map<String, Object> query(@RequestParam(defaultValue = "50") int limit) {
        String tenant = TenantContext.tenantId();
        List<AuditLogger.AuditEntry> entries = audit.query(tenant, Math.min(limit, 500));
        return Map.of(
                "code", 0,
                "data", Map.of(
                        "tenant", tenant,
                        "total", entries.size(),
                        "entries", entries.stream().map(e -> Map.of(
                                "ts", e.ts().toString(),
                                "action", e.action(),
                                "target", e.target(),
                                "user", e.userId() == null ? "" : e.userId(),
                                "success", e.success(),
                                "costMs", e.costMs(),
                                "detail", e.detail() == null ? "" : e.detail())).toList()));
    }
}
