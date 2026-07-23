package com.armada.marketing.grouppull.model.enums;

/** 拉群营销任务当前执行阻塞原因。 */
public enum GroupPullBlockReason {

    /** 无阻塞。 */
    NONE(0),

    /** 等待可用建群账号。 */
    WAITING_BUILDER(1),

    /** 等待可用营销账号。 */
    WAITING_MARKETER(2),

    /** 等待足量料子数据。 */
    WAITING_MATERIAL(3),

    /** 系统依赖异常，等待恢复。 */
    SYSTEM_ERROR(4),

    /** 存在结果不明确的操作，需要人工处理。 */
    MANUAL_REVIEW(5);

    private final int code;

    GroupPullBlockReason(int code) {
        this.code = code;
    }

    /** 返回数据库持久化码值。 */
    public int code() {
        return code;
    }

    /** 按数据库码值解析阻塞原因。 */
    public static GroupPullBlockReason fromCode(int code) {
        return switch (code) {
            case 0 -> NONE;
            case 1 -> WAITING_BUILDER;
            case 2 -> WAITING_MARKETER;
            case 3 -> WAITING_MATERIAL;
            case 4 -> SYSTEM_ERROR;
            case 5 -> MANUAL_REVIEW;
            default -> throw new IllegalArgumentException("未知拉群阻塞原因: " + code);
        };
    }
}
