package com.armada.task.model.enums;

/** 逐号码单次执行的生命周期；协议事实与生命周期分列保存。 */
public enum PullTaskParticipantAttemptStatus {

    /** 已规划但批次命令尚未提交。 */
    PLANNED(1),

    /** 已随批次命令提交，等待逐号码事实。 */
    SUBMITTED(2),

    /** 本次执行已经得到明确收口。 */
    CLOSED(3),

    /** 结果未知且占用已释放，可由新批次接管。 */
    RELEASED(4),

    /** 尚未提交的本次规划已取消。 */
    CANCELED(5);

    private final int code;

    PullTaskParticipantAttemptStatus(int code) {
        this.code = code;
    }

    /** @return 数据库存储值 */
    public int code() {
        return code;
    }

    /** @return 该状态是否继续占用参与者活动槽位 */
    public static boolean active(int code) {
        return code == PLANNED.code || code == SUBMITTED.code;
    }
}
