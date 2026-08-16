package com.armada.task.model.enums;

/** 普通群链接执行链路的持久化原因码与脱敏说明。 */
public enum PullTaskExecutionReasonCode {

    /** 历史兼容原因码；新任务由协议进群结果判断链接是否失效。 */
    LINK_INVALID("群链接已失效"),

    /** 历史兼容原因码；取消公开邀请页预检后不再生成。 */
    LINK_PROBE_INCOMPLETE("群链接校验暂不可用"),

    /** 管理分组当前没有可执行协议动作的在线正常账号。 */
    MANAGER_UNAVAILABLE("当前没有可用管理员"),

    /** 管理账号已提交入群申请，尚未确认在群。 */
    MANAGER_JOIN_PENDING_APPROVAL("管理员已提交入群申请，等待群主或管理员审批；该群拉群已暂停"),

    /** 协议调用或实时成员查询没有形成可确认的在群结果。 */
    MANAGER_MEMBERSHIP_UNCONFIRMED("管理员在群结果无法确认"),

    /** 群内没有在线、正常且协议身份完整的我方群主或管理员。 */
    MANAGER_ADMIN_ACTOR_UNAVAILABLE("当前没有在线的我方群主或管理员"),

    /** 候选提权动作明确失败，当前没有其他可立即使用的候选。 */
    MANAGER_ADMIN_SETUP_FAILED("管理员设置失败"),

    /** 提权命令已有结果，但实时成员列表尚未确认目标权限。 */
    MANAGER_ADMIN_UNCONFIRMED("管理员权限结果暂未确认"),

    /** 管理员账号尚未确认普通成员可以添加群成员。 */
    GROUP_MEMBER_ADD_PERMISSION_UNCONFIRMED("普通成员添加群成员权限尚未确认"),

    /** 当前管理账号没有修改普通成员添加群成员权限的管理员权限。 */
    GROUP_MEMBER_ADD_PERMISSION_DENIED("管理员账号无权开启普通成员添加群成员权限"),

    /**
     * 关闭群进群审核失败。
     *
     * <p>只写入群设置动作行的 {@code reason_code} 供排查与统计，不写入执行行、不阻断阶段推进，
     * 也不重试。拉手与料子由管理员 add 进群，不受进群审批门控；该设置只影响踩链接自主进群的
     * 补充管理员。</p>
     */
    GROUP_JOIN_APPROVAL_CLOSE_FAILED("关闭进群审核失败"),

    /** WhatsApp 明确通知目标群已暂停或终止。 */
    GROUP_BANNED("群已被封禁"),

    /** 协议明确通知目标群已满、不存在、不可访问或无法继续拉人。 */
    GROUP_UNAVAILABLE("群当前不可继续执行拉人"),

    /** 拉手分组当前没有可占用且可执行协议动作的在线正常账号。 */
    PULLER_UNAVAILABLE("当前没有可用拉手"),

    /** 站台分组中同群未使用的在线账号不足本次调用配置数。 */
    STATION_UNAVAILABLE("当前可用站台不足");

    private final String message;

    PullTaskExecutionReasonCode(String message) {
        this.message = message;
    }

    /** @return 可安全落库和展示的脱敏说明 */
    public String message() {
        return message;
    }
}
