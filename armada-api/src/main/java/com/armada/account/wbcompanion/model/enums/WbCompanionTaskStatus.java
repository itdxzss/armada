package com.armada.account.wbcompanion.model.enums;

/** 登录到 WB 分身任务的状态（wb_companion_task.status）。 */
public final class WbCompanionTaskStatus {

    /** 进行中：调度器按条推进。 */
    public static final int RUNNING = 1;
    /** 成功：WB 上的连接数已达到目标。 */
    public static final int SUCCEEDED = 2;
    /** 失败：某一条失败后停下，已成功的连接保留。 */
    public static final int FAILED = 3;
    /** 已停止：操作人停止剩余的连接。 */
    public static final int STOPPED = 4;

    private WbCompanionTaskStatus() {
    }
}
