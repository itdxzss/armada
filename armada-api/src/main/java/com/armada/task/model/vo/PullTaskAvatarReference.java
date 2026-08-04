package com.armada.task.model.vo;

/** 已绑定普通群链接任务的头像引用。 */
public record PullTaskAvatarReference(
        long tenantId,
        long taskId,
        String avatarFileKey) {
}
