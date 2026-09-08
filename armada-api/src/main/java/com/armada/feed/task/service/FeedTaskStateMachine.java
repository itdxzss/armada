package com.armada.feed.task.service;

import com.armada.feed.task.model.enums.FeedTaskAction;
import com.armada.feed.task.model.enums.FeedTaskRunStatus;

import java.util.Optional;

/** 动态发布任务状态机。 */
public final class FeedTaskStateMachine {

    private FeedTaskStateMachine() {
    }

    /** 计算一次动作后的目标运行状态。 */
    public static Optional<FeedTaskRunStatus> next(FeedTaskRunStatus current, FeedTaskAction action) {
        if (current == null || action == null) {
            return Optional.empty();
        }
        return switch (current) {
            case NOT_STARTED -> action == FeedTaskAction.START
                    ? Optional.of(FeedTaskRunStatus.RUNNING)
                    : Optional.empty();
            case RUNNING -> switch (action) {
                case PAUSE -> Optional.of(FeedTaskRunStatus.PAUSED);
                case STOP -> Optional.of(FeedTaskRunStatus.STOPPED);
                default -> Optional.empty();
            };
            case PAUSED -> switch (action) {
                case RESUME -> Optional.of(FeedTaskRunStatus.RUNNING);
                case STOP -> Optional.of(FeedTaskRunStatus.STOPPED);
                default -> Optional.empty();
            };
            case COMPLETED, STOPPED -> Optional.empty();
        };
    }

    /** 未开始的任务允许编辑。 */
    public static boolean isEditable(FeedTaskRunStatus current) {
        return current == FeedTaskRunStatus.NOT_STARTED;
    }
}
