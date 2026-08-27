package com.armada.shared.security;

import java.util.Objects;
import java.util.Optional;

/**
 * 当前线程的数据范围上下文。
 *
 * <p>使用普通 {@link ThreadLocal}，禁止把 HTTP 身份隐式传播到线程池。异步入口必须从持久化
 * 聚合根显式恢复范围，并通过 {@link #open(DataScope)} 的 try-with-resources 自动还原。</p>
 */
public final class DataScopeContext {

    private static final ThreadLocal<DataScope> CONTEXT = new ThreadLocal<>();

    private DataScopeContext() {
    }

    /** 返回当前范围；缺少上下文时返回空，调用方不得把空解释成 ALL。 */
    public static Optional<DataScope> current() {
        return Optional.ofNullable(CONTEXT.get());
    }

    /**
     * 返回当前范围并在缺失时失败关闭。
     *
     * @return 当前线程范围
     * @throws IllegalStateException 当前线程没有显式范围时抛出
     */
    public static DataScope requireCurrent() {
        DataScope scope = CONTEXT.get();
        if (scope == null) {
            throw new IllegalStateException("当前线程缺少 DataScope");
        }
        return scope;
    }

    /**
     * 打开一个可嵌套范围，关闭时恢复之前的范围。
     *
     * @param scope 本段执行使用的显式范围
     * @return 可重复关闭且只恢复一次的上下文句柄
     */
    public static Scope open(DataScope scope) {
        Objects.requireNonNull(scope, "DataScope 不能为空");
        DataScope previous = CONTEXT.get();
        CONTEXT.set(scope);
        return new Scope(previous);
    }

    /** 清理当前线程范围，供请求边界和线程池兜底。 */
    public static void clear() {
        CONTEXT.remove();
    }

    /** {@link #open(DataScope)} 返回的幂等恢复句柄。 */
    public static final class Scope implements AutoCloseable {

        private final DataScope previous;
        private boolean closed;

        private Scope(DataScope previous) {
            this.previous = previous;
        }

        /** 恢复打开本范围前的上下文；重复调用不产生额外效果。 */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CONTEXT.remove();
            } else {
                CONTEXT.set(previous);
            }
        }
    }
}
