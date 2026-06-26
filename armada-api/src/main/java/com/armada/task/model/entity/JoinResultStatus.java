package com.armada.task.model.entity;

/**
 * 进群任务结果状态码常量。
 */
public final class JoinResultStatus {

    private JoinResultStatus() {
        throw new AssertionError("No instances");
    }

    /** 待执行。 */
    public static final String PENDING = "PENDING";

    /** 成功。 */
    public static final String SUCCESS = "SUCCESS";

    /** 失败。 */
    public static final String FAILED = "FAILED";
}
