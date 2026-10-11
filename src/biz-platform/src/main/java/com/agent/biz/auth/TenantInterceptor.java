package com.agent.biz.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 租户上下文注入拦截器：从请求头解析租户/用户，注入 TenantContext，请求结束清理。
 *
 * 对应 docs/03_模块设计/03_05_auth_rbac.md。
 * 生产：租户 ID 来自网关解析的 JWT；此处从 X-Tenant-Id / X-User-Id 头解析（demo 简化）。
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    public static final String HEADER_TENANT = "X-Tenant-Id";
    public static final String HEADER_USER = "X-User-Id";

    /** 默认租户：无租户头时使用（兼容 demo/评估，生产必须强制租户）。 */
    public static final String DEFAULT_TENANT = "tenant-0";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        String tenant = request.getHeader(HEADER_TENANT);
        if (tenant == null || tenant.isBlank()) {
            tenant = DEFAULT_TENANT;
        }
        Long userId = parseLong(request.getHeader(HEADER_USER));
        TenantContext.set(tenant, userId, null);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
    }

    private Long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
