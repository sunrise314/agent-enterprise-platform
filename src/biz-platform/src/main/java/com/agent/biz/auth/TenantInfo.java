package com.agent.biz.auth;

/**
 * 租户/用户信息载体，随 RuntimeContext 注入工具方法。
 *
 * AgentScope 的 @Tool 方法可声明一个非 @ToolParam 的参数（如 TenantInfo tenant），
 * 框架会按类型从 RuntimeContext（builder.put(TenantInfo.class, value)）自动注入，
 * 从而在响应式执行链路中把租户传给工具，避免 ThreadLocal 跨线程丢失。
 */
public record TenantInfo(String tenantId, Long userId) {

    public static TenantInfo of(String tenantId, Long userId) {
        return new TenantInfo(tenantId == null ? "tenant-0" : tenantId, userId);
    }
}
