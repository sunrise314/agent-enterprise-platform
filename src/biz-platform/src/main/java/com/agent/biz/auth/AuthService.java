package com.agent.biz.auth;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RBAC 权限服务（内存演示版）。
 *
 * 用户 → 角色 → 权限（RBAC）。
 * 生产：用户/角色/权限入库（PostgreSQL），网关解析 JWT 后查询权限。
 *
 * 对应 docs/03_模块设计/03_05_auth_rbac.md
 */
@Service
public class AuthService {

    /** 用户 → 角色（demo 硬编码，生产查库）。 */
    private static final Map<Long, String> USER_ROLE = Map.of(
            1L, "ADMIN",
            2L, "EMPLOYEE");

    /** 角色 → 权限集合。 */
    private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
            "ADMIN", Set.of("agent:chat", "agent:manage", "eval:run", "kb:index", "audit:view"),
            "EMPLOYEE", Set.of("agent:chat"));

    /** 匿名访问权限（无需登录）。 */
    private static final Set<String> PUBLIC_PERMISSIONS = Set.of("health");

    /**
     * 判断指定用户是否具备某权限。
     *
     * @param userId     用户 ID（null 视为匿名）
     * @param permission 所需权限
     */
    public boolean hasPermission(Long userId, String permission) {
        if (PUBLIC_PERMISSIONS.contains(permission)) {
            return true;
        }
        if (userId == null) {
            return false;
        }
        String role = USER_ROLE.getOrDefault(userId, "EMPLOYEE");
        return ROLE_PERMISSIONS.getOrDefault(role, Set.of()).contains(permission);
    }

    /** 用户拥有的权限列表。 */
    public List<String> permissionsOf(Long userId) {
        if (userId == null) {
            return List.of();
        }
        String role = USER_ROLE.getOrDefault(userId, "EMPLOYEE");
        return List.copyOf(ROLE_PERMISSIONS.getOrDefault(role, Set.of()));
    }

    /** 用户角色。 */
    public String roleOf(Long userId) {
        return userId == null ? "ANONYMOUS" : USER_ROLE.getOrDefault(userId, "EMPLOYEE");
    }
}
