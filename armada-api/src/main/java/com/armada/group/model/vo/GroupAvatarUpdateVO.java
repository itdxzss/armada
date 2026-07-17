package com.armada.group.model.vo;

/**
 * 群头像更新结果。
 *
 * @param applied      WhatsApp 群头像是否已应用
 * @param mirrorSynced Armada 本地头像镜像是否已同步
 * @param avatarUrl    协议层回读的头像 URL;无法回读时为 null
 */
public record GroupAvatarUpdateVO(
        boolean applied,
        boolean mirrorSynced,
        String avatarUrl
) {
}
