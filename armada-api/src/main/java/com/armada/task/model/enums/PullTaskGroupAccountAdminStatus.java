package com.armada.task.model.enums;

/** 管理角色在目标群中的实时管理员权限状态。 */
public enum PullTaskGroupAccountAdminStatus {

    /** 非管理角色不适用。 */
    NOT_APPLICABLE(0),
    /** 尚未通过实时成员列表确认。 */
    PENDING(1),
    /** 权限设置命令已提交。 */
    SUBMITTED(2),
    /** 已通过实时成员列表确认管理员或群主权限。 */
    SUCCESS(3),
    /** 已确认不具备管理员权限。 */
    FAILED(4),
    /** 实时权限结果暂时无法确认。 */
    UNKNOWN(5);

    private final int code;

    PullTaskGroupAccountAdminStatus(int code) {
        this.code = code;
    }

    /** @return 数据库存储值 */
    public int code() {
        return code;
    }
}
