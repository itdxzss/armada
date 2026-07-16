package com.armada.task.scheduler;

/**
 * 单轮进群调度统计。
 *
 * <p>只保存聚合数量，日志可以安全输出，不包含租户、账号、手机号或群邀请码。</p>
 *
 * @param scanned 跨租户预扫描命中的候选数
 * @param claimed 单租户事务复核并成功锁定的明细数
 * @param enqueued 成功写入协议 outbox 的命令数
 * @param skipped 锁定后因账号或链接无效而直接终止的明细数
 */
public record JoinTaskDispatchStats(int scanned, int claimed, int enqueued, int skipped) {

    /**
     * 创建所有计数均为 0 的统计对象。
     *
     * @return 空统计
     */
    public static JoinTaskDispatchStats empty() {
        return new JoinTaskDispatchStats(0, 0, 0, 0);
    }

    /**
     * 合并两个租户或两个阶段的调度统计。
     *
     * @param other 待合并统计
     * @return 各计数字段相加后的新统计对象
     */
    public JoinTaskDispatchStats plus(JoinTaskDispatchStats other) {
        return new JoinTaskDispatchStats(
                scanned + other.scanned,
                claimed + other.claimed,
                enqueued + other.enqueued,
                skipped + other.skipped);
    }
}
