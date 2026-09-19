package com.armada.task.model.enums;

/** 清理与退群严格串行；发送中超时只失败，不重新执行外部动作。 */
public enum JoinTaskCleanupStatus {
    /** 未开启或尚未进入清理阶段。 */ NOT_REQUIRED(0),
    /** 提权成功，等待读取清理名单。 */ WAITING(1),
    /** 正在读取名单。 */ LISTING(2),
    /** 等待移除当前管理员。 */ REMOVE_READY(3),
    /** 当前成员移除请求已发起。 */ REMOVING(4),
    /** 所有目标移除成功，等待原号退群。 */ LEAVE_READY(5),
    /** 原号退群请求已发起。 */ LEAVING(6),
    /** 清理和退群全部成功。 */ SUCCESS(7),
    /** 失败或结果不明，后续停止。 */ FAILED(8);

    private final int code;
    JoinTaskCleanupStatus(int code) { this.code = code; }
    /** 数据库阶段码。 */
    public int code() { return code; }
    /** 是否仍阻止任务完成。 */
    public boolean pending() { return code > 0 && code < SUCCESS.code; }
    /** 是否已有在途调用，禁止自动重发。 */
    public boolean inFlight() { return this == LISTING || this == REMOVING || this == LEAVING; }
    /** 解析数据库阶段。 */
    public static JoinTaskCleanupStatus of(int code) {
        for (var value : values()) if (value.code == code) return value;
        throw new IllegalArgumentException("未知清理阶段: " + code);
    }
}
