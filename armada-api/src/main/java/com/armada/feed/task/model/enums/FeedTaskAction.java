package com.armada.feed.task.model.enums;

import java.util.Locale;

/** 动态发布任务动作。 */
public enum FeedTaskAction {
    START,
    PAUSE,
    RESUME,
    STOP;

    public static FeedTaskAction fromWire(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("任务动作不能为空");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (FeedTaskAction action : values()) {
            if (action.name().equals(normalized)) {
                return action;
            }
        }
        throw new IllegalArgumentException("未知的动态发布任务动作: " + value);
    }
}
