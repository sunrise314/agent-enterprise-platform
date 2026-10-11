package com.agent.biz.auth;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口：登录签发 JWT。
 *
 * demo 内置账号：
 *  - admin / admin123    → userId=1, role=ADMIN, tenant=tenant-0
 *  - employee / pass123  → userId=2, role=EMPLOYEE, tenant=tenant-0
 * 生产接入企业 SSO / 数据库账号校验。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtUtil jwt;
    private final AuthService authService;

    public AuthController(JwtUtil jwt, AuthService authService) {
        this.jwt = jwt;
        this.authService = authService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest req) {
        Map.Entry<Long, String> account = resolve(req.username(), req.password());
        if (account == null) {
            return Map.of("code", 1, "msg", "invalid credentials");
        }
        Long userId = account.getKey();
        String role = account.getValue();
        String tenantId = "tenant-0";
        String token = jwt.generate(userId, tenantId, role);
        return Map.of("code", 0, "data", Map.of(
                "token", token,
                "userId", userId,
                "role", role,
                "tenantId", tenantId,
                "permissions", authService.permissionsOf(userId)));
    }

    /** demo 账号校验（生产查库 / SSO）。 */
    private Map.Entry<Long, String> resolve(String username, String password) {
        if ("admin".equals(username) && "admin123".equals(password)) {
            return Map.entry(1L, "ADMIN");
        }
        if ("employee".equals(username) && "pass123".equals(password)) {
            return Map.entry(2L, "EMPLOYEE");
        }
        return null;
    }

    public record LoginRequest(String username, String password) {
    }
}
