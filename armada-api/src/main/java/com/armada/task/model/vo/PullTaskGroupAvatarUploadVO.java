package com.armada.task.model.vo;

/** 拉群任务群头像上传结果。 */
public record PullTaskGroupAvatarUploadVO(
        String avatarFileKey,
        String originalFileName,
        String previewUrl) {
}
