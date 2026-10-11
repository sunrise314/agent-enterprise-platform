package com.agent.biz.auth;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * 认证 + RBAC 拦截器。
 *
 * 逻辑：
 *  - 有 Authorization: Bearer token → 解析校验 → 按路径所需权限校验（AuthService）→ 注入 TenantContext
 *  - 无 token → 回退 X-Tenant-Id / X-User-Id 头（demo 兼容；生产必须强制 token）
 *  - 登录/健康检查放行
 *
 * 对应 docs/03_模块设计/03_05_auth_rbac.md。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwt;
    private final AuthService authService;

    /** 路径前缀 → 所需权限。 */
    private static final Map<String, String> PATH_PERMISSION = Map.of(
            "/api/agent", "agent:chat",
            "/api/eval", "eval:run",
            "/api/audit", "audit:view");

    public AuthInterceptor(JwtUtil jwt, AuthService authService) {
        this.jwt = jwt;
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String path = request.getRequestURI();
        if (path.startsWith("/api/auth/") || path.startsWith("/health")) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return handleToken(path, authHeader.substring(7), request, response);
        }

        // 无 token：demo 回退 header（生产应拒绝未认证请求）
        String tenant = request.getHeader("X-Tenant-Id");
        TenantContext.set(tenant == null || tenant.isBlank() ? "tenant-0" : tenant,
                parseLong(request.getHeader("X-User-Id")), null);
        return true;
    }

    private boolean handleToken(String path, String token,
                                HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        try {
            Claims claims = jwt.parse(token);
            Long userId = Long.valueOf(claims.getSubject());
            String tenant = claims.get("tenantId", String.class);
            String role = claims.get("role", String.class);

            String required = requiredPermission(path);
            if (required != null && !authService.hasPermission(userId, required)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN,
                        "permission denied, need: " + required);
                return false;
            }
            TenantContext.set(tenant, userId, null);
            return true;
        } catch (Exception e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "invalid or expired token");
            return false;
        }
    }

    /** 请求路径所需权限（未匹配返回 null = 不校验）。 */
    private String requiredPermission(String path) {
        for (Map.Entry<String, String> e : PATH_PERMISSION.entrySet()) {
            if (path.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
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
