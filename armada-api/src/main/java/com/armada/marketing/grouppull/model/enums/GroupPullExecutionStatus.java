package com.armada.marketing.grouppull.model.enums;

/** 单个建群账号的执行结果状态。 */
public enum GroupPullExecutionStatus {

    /** 建群前资源与好友准备中。 */
    PREPARING(1),

    /** 已进入正式建群流程。 */
    EXECUTING(2),

    /** 完整建群成功。 */
    SUCCEEDED(3),

    /** 正式建群流程失败。 */
    FAILED(4),

    /** 建群前失败并跳过，不计建群失败。 */
    PRE_GROUP_SKIPPED(5),

    /** 任务释放时取消的准备记录。 */
    CANCELED(6),

    /** 外部结果不明确，需要人工处理。 */
    MANUAL_REVIEW(7);

    private final int code;

    GroupPullExecutionStatus(int code) {
        this.code = code;
    }

    /** 返回数据库持久化码值。 */
    public int code() {
        return code;
    }

    /** 按数据库码值解析执行状态。 */
    public static GroupPullExecutionStatus fromCode(int code) {
        return switch (code) {
            case 1 -> PREPARING;
            case 2 -> EXECUTING;
            case 3 -> SUCCEEDED;
            case 4 -> FAILED;
            case 5 -> PRE_GROUP_SKIPPED;
            case 6 -> CANCELED;
            case 7 -> MANUAL_REVIEW;
            default -> throw new IllegalArgumentException("未知拉群执行状态: " + code);
        };
    }
}
