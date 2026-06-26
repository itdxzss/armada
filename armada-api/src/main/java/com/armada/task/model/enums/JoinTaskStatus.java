package com.armada.task.model.enums;

/**
 * 进群任务状态码常量。
 */
public final class JoinTaskStatus {

    private JoinTaskStatus() {
        throw new AssertionError("No instances");
    }

    /** 待启动。 */
    public static final String DRAFT = "DRAFT";

    /** 进行中。 */
    public static final String RUNNING = "RUNNING";

    /** 暂停。 */
    public static final String PAUSED = "PAUSED";

    /** 已停止。 */
    public static final String STOPPED = "STOPPED";

    /** 完成。 */
    public static final String DONE = "DONE";

    /** 失败。 */
    public static final String FAILED = "FAILED";
}
