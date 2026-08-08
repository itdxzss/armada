package com.armada.task.scheduler;

/** 一条执行行的未知协议结果收敛统计。 */
public record PullTaskUnknownResultReconciliationStats(
        int confirmed,
        int markedUnknown) {

    /** @return 零值统计 */
    public static PullTaskUnknownResultReconciliationStats empty() {
        return new PullTaskUnknownResultReconciliationStats(0, 0);
    }

    /** @return 新版逐号码核实后释放回待拉池的数量 */
    public int released() {
        return markedUnknown;
    }
}
