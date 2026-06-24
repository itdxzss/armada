package com.armada.shared.tenant;

/**
 * 当前租户上下文(ThreadLocal)。
 *
 * <p>登录时由 Web 鉴权拦截器写入 tenant_id;租户行隔离拦截器读取它给每条 SQL 注入
 * {@code AND tenant_id = ?}。无上下文时回退哨兵值 -1(fail-closed,查不到任何业务数据,防越权泄漏)。</p>
 *
 * <p>铁律:ThreadLocal 绑在线程上——脱离 HTTP 请求线程的路径(@Async/线程池/Kafka/定时任务)
 * 必须显式重建上下文;线程池复用前必须 {@link #clear()},否则残留租户串号。</p>
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CTX = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Long tenantId) {
        CTX.set(tenantId);
    }

    public static Long get() {
        return CTX.get();
    }

    public static void clear() {
        CTX.remove();
    }
}
