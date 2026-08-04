package com.armada.task.model.enums;

/** 普通群链接执行链路的持久化原因码与脱敏说明。 */
public enum PullTaskExecutionReasonCode {

    /** 公开邀请页可达，但没有任何真实群资料。 */
    LINK_INVALID("群链接已失效"),

    /** 公开邀请页暂时不可达，不能据此判定链接失效。 */
    LINK_PROBE_INCOMPLETE("群链接校验暂不可用"),

    /** 管理分组当前没有可执行协议动作的在线正常账号。 */
    MANAGER_UNAVAILABLE("当前没有可用管理员"),

    /** 管理账号已提交入群申请，尚未确认在群。 */
    MANAGER_JOIN_PENDING_APPROVAL("管理员入群等待审批"),

    /** 协议调用或实时成员查询没有形成可确认的在群结果。 */
    MANAGER_MEMBERSHIP_UNCONFIRMED("管理员在群结果无法确认"),

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
