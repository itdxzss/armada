package com.armada.task.model.enums;

/** 待审核恢复状态；成功只表示已确认入群，后续提权仍独立执行。 */
public enum JoinTaskApprovalStage {
    /** 收到待审核后解析本群和原管理员。 */
    RESOLVE(1, "进群待审核，正在查找原群管理员"),
    /** 原管理员关闭审核开关。 */
    CLOSE(2, "正在关闭群组审核"),
    /** 关闭成功后查看当前账号成员与申请事实。 */
    CHECK(3, "审核已关闭，正在确认进群"),
    /** 仅放行当前明细对应的申请。 */
    APPROVE(4, "审核已关闭，正在处理当前进群申请"),
    /** 已确认没有成员或待审申请，允许一次续进群。 */
    REJOIN(5, "审核已关闭，正在继续进群"),
    /** 只读确认，不能重复提交批准或入群请求。 */
    VERIFY(6, "审核已关闭，正在确认进群结果"),
    /** 已确认账号在群内。 */
    SUCCESS(7, "已完成进群"),
    /** 处理失败或结果未确认，后续停止。 */
    FAILED(8, "自动处理失败");

    private final int code;
    private final String label;
    JoinTaskApprovalStage(int code, String label) { this.code = code; this.label = label; }
    /** 持久化代码。 */
    public int code() { return code; }
    /** 用户可见进度。 */
    public String label() { return label; }
    /** 是否仍需处理。 */
    public boolean pending() { return this != SUCCESS && this != FAILED; }
    /** 读取数据库状态，未知值不允许推进。 */
    public static JoinTaskApprovalStage of(int code) {
        for (var value : values()) if (value.code == code) return value;
        throw new IllegalArgumentException("未知的进群审核处理状态");
    }
}
