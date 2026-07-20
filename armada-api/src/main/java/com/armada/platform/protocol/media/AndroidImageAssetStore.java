package com.armada.platform.protocol.media;

/**
 * Android 图片原图缓存端口。
 */
public interface AndroidImageAssetStore {

    /**
     * 保证图片在 outbox 落库前已存在于共享 Redis；已存在时只刷新有效期。
     *
     * @param asset 待保证可读取的租户级图片资源
     * @throws RuntimeException 当 Redis 无法保证图片可用时抛出
     */
    void ensure(AndroidImageAsset asset);
}
