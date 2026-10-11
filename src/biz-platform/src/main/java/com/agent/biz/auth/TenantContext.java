package com.agent.biz.auth;

/**
 * 租户上下文：网关鉴权后注入，贯穿一次请求。
 *
 * 对应 docs/03_模块设计/03_05_auth_rbac.md
 * 所有业务查询 / Agent 执行必须强制附加租户过滤，防止跨租户数据泄露。
 */
public final class TenantContext {

    private TenantContext() {
    }

    private static final ThreadLocal<Tenant> HOLDER = new ThreadLocal<>();

    public static void set(String tenantId, Long userId, String username) {
        HOLDER.set(new Tenant(tenantId, userId, username));
    }

    public static Tenant get() {
        return HOLDER.get();
    }

    public static String tenantId() {
        Tenant t = HOLDER.get();
        return t == null ? null : t.tenantId();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public record Tenant(String tenantId, Long userId, String username) {
    }
}
