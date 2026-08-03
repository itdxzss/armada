package com.armada.task.model.enums;

/** 执行行内的账号动作类型；与 pull_task_account_action.action_type 一一对应。 */
public enum PullTaskAccountActionType {

    /** 保存联系人：单方向动作，双向加好友由 actor/target 互换的两行表达。 */
    SAVE_CONTACT(1),
    /** 邀请入群：管理账号邀请拉手，或补充管理员时由现有管理员邀请。 */
    INVITE_TO_GROUP(2),
    /** 踩链接入群：账号自行通过群链接进入，actor 写目标账号自身 ID。 */
    JOIN_BY_LINK(3);

    private final int code;

    PullTaskAccountActionType(int code) {
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
