package com.armada.shared.exception;

/**
 * 暂时性并发冲突。
 *
 * <p>目标数据此刻被调度租约等占用，本次写入必须整体回滚；占用释放后原样重试即可成功。
 * Kafka 消费者遇到它按较长退避重试，覆盖一轮调度的持锁时长，而不是几秒内就投递死信。
 * 继承 {@link IllegalStateException}，保持既有按状态异常处理的调用方行为不变。</p>
 */
public class RetryableConflictException extends IllegalStateException {

    /**
     * @param message 冲突说明，写入日志便于排查
     */
    public RetryableConflictException(String message) {
        super(message);
    }
}
