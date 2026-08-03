package com.armada.task.model.enums;

/** 角色账号在目标群中的在群状态；与 pull_task_group_account.membership_status 一一对应。 */
public enum PullTaskGroupAccountMembershipStatus {

    /** 未入群：尚未发起入群动作。 */
    NOT_JOINED(0),
    /** 入群中：入群命令已发出，等待结果。 */
    JOINING(1),
    /** 在群：已确认在群，可承担后续职责。 */
    IN_GROUP(2),
    /** 入群失败：明确失败，终态。 */
    JOIN_FAILED(3),
    /** 结果未知：由查询或回调收敛。 */
    UNKNOWN(4);

    private final int code;

    PullTaskGroupAccountMembershipStatus(int code) {
        this.code = code;
    }

    /**
     * 数据库存储值。
     *
     * @return TINYINT 取值
     */
    public int code() {
        return code;
    }
}
