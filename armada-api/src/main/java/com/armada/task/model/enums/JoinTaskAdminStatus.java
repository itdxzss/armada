package com.armada.task.model.enums;

import com.armada.task.model.entity.JoinTaskResult;

/** 管理员阶段与进群结果独立，保留已进群事实。 */
public enum JoinTaskAdminStatus {
    /** 未启用。 */ NOT_REQUIRED(0),
    /** 等待进群成功或可用管理员。 */ WAITING(1),
    /** 已写入 Outbox，等待业务回执。 */ SUBMITTED(2),
    /** 已由成员级回执或当前角色确认。 */ SUCCESS(3),
    /** 已失败或核实期限耗尽。 */ FAILED(4),
    /** 副作用结果未知，必须先查询。 */ UNKNOWN(5);

    private final int code;
    JoinTaskAdminStatus(int code) { this.code = code; }
    /** 返回持久化码。 */
    public int code() { return code; }
    /** 解析持久化码，未知码不能误判为未开启。 */
    public static JoinTaskAdminStatus of(int code) {
        for (JoinTaskAdminStatus value : values()) {
            if (value.code == code) return value;
        }
        throw new IllegalArgumentException("未知管理员阶段: " + code);
    }
    /** 派生整条步骤结果，不能仅根据进群成功结单。 */
    public static String stepStatus(JoinTaskResult row) {
        if ("FAILED".equals(row.getStatus()) || row.getAdminStatus() == FAILED.code) return "FAILED";
        if (row.getCleanupStatus() == JoinTaskCleanupStatus.FAILED.code()) return "FAILED";
        if (JoinTaskCleanupStatus.of(row.getCleanupStatus()).pending()) return "PENDING";
        if (!"SUCCESS".equals(row.getStatus())) return "PENDING";
        return row.getAdminStatus() == NOT_REQUIRED.code || row.getAdminStatus() == SUCCESS.code
                ? "SUCCESS" : "PENDING";
    }
}
