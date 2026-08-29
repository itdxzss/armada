package com.armada.hyperlink.task.model.enums;

/** 超链任务内账号使用状态数据库码。 */
public enum HyperlinkTaskAccountUsageStatus {
    /** 仍可继续分配 recipient。 */
    AVAILABLE(1),
    /** 已达到任务成功上限。 */
    LIMIT_REACHED(2),
    /** 协议明确账号封禁。 */
    BANNED(3),
    /** 设备删除、退出登录等明确失效。 */
    INVALID(4),
    /** 运营人工停用。 */
    DISABLED(5);

    private final int code;

    HyperlinkTaskAccountUsageStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
