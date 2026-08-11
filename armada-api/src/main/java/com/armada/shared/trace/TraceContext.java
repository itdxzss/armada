package com.armada.shared.trace;

import org.slf4j.MDC;

import java.util.Optional;

/**
 * 基于 SLF4J MDC 的当前线程追踪上下文。
 */
public final class TraceContext {

    /** MDC 中保存追踪标识的键。 */
    public static final String MDC_KEY = "traceId";

    private TraceContext() {
    }

    /**
     * 读取当前线程中的合法追踪标识。
     *
     * @return 当前追踪标识，无上下文时为空
     */
    public static Optional<String> current() {
        return TraceIds.normalize(MDC.get(MDC_KEY));
    }

    /**
     * 建立追踪 Scope；候选值非法时生成新值。
     *
     * @param candidateTraceId 待写入的追踪标识
     * @return 关闭后恢复原 MDC 状态的 Scope
     */
    public static Scope open(String candidateTraceId) {
        String traceId = TraceIds.normalize(candidateTraceId).orElseGet(TraceIds::newTraceId);
        String previousTraceId = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, traceId);
        return new Scope(previousTraceId);
    }

    /**
     * 可自动恢复上一层追踪上下文的作用域。
     */
    public static final class Scope implements AutoCloseable {

        private final String previousTraceId;
        private boolean closed;

        private Scope(String previousTraceId) {
            this.previousTraceId = previousTraceId;
        }

        /** 恢复 Scope 建立前的 MDC 状态。 */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previousTraceId == null) {
                MDC.remove(MDC_KEY);
                return;
            }
            MDC.put(MDC_KEY, previousTraceId);
        }
    }
}
