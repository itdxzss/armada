package com.armada.feed.task.model.enums;

/** 动态发布任务运行状态。 */
public enum FeedTaskRunStatus {
    NOT_STARTED(0),
    RUNNING(1),
    COMPLETED(2),
    PAUSED(3),
    STOPPED(4);

    private final int code;

    FeedTaskRunStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static FeedTaskRunStatus fromCode(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("动态发布任务状态不能为空");
        }
        for (FeedTaskRunStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的动态发布任务状态: " + code);
    }
}
