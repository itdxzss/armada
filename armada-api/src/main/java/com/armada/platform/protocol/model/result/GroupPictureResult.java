package com.armada.platform.protocol.model.result;

/**
 * 群头像更新结果。
 *
 * @param applied   协议层是否已应用更新
 * @param avatarUrl 更新后回读的头像 URL;无法回读时为 null
 */
public record GroupPictureResult(boolean applied, String avatarUrl) {
}
