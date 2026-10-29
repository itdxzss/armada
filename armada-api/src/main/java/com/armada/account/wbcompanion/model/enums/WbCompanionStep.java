package com.armada.account.wbcompanion.model.enums;

/** 任务当前这一条连接的步骤（wb_companion_task.step）。 */
public final class WbCompanionStep {

    /** 待发起：下一步让 WB 用新配对码发起配对。 */
    public static final int WAITING = 0;
    /** WB 已发起配对：下一步让安卓主设备输入同一个配对码。 */
    public static final int WB_STARTED = 1;
    /** 安卓已输入配对码：下一步等 WB 确认上线。 */
    public static final int CODE_SUBMITTED = 2;

    private WbCompanionStep() {
    }
}
