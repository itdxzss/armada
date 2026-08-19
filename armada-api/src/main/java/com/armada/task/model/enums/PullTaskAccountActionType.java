package com.armada.task.model.enums;

/** 执行行内的账号动作类型；与 pull_task_account_action.action_type 一一对应。 */
public enum PullTaskAccountActionType {

    /** 保存联系人：单方向动作，双向加好友由 actor/target 互换的两行表达。 */
    SAVE_CONTACT(1),
    /** 邀请入群：管理账号邀请拉手，或补充管理员时由现有管理员邀请。 */
    INVITE_TO_GROUP(2),
    /** 踩链接入群：账号自行通过群链接进入，actor 写目标账号自身 ID。 */
    JOIN_BY_LINK(3),
    /** 设置任务管理员：群内既有管理员把任务管理员提升为群管理员。 */
    PROMOTE_MANAGER(4),
    /** 放开加人权限：任务管理员把群设置为全体成员可添加成员，是拉手拉料子的硬前置。 */
    OPEN_MEMBER_ADD(5),
    /** 关闭进群审核：任务管理员关闭群的管理员入群审批，失败不阻断执行行。 */
    CLOSE_JOIN_APPROVAL(6),
    /**
     * 应用「群信息设置」：任务管理员按任务表单一次性下发群名、群头像、群描述、禁言、
     * 群资料编辑权限与限时消息，走专用的 {@code group.profile.apply} 命令
     * （不与建群链路、也不与上面两个单项设置命令混用）。
     *
     * <p>每条执行行至多一行；失败不阻断执行行，且<b>只发一次</b>——协议侧回 UNKNOWN 也不重发，
     * 只留 reason_code 供排查与统计。口径与理由见 PullTaskGroupSettingsResultServiceImpl 类注释。</p>
     */
    APPLY_GROUP_SETTINGS(7);

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
