package com.armada.platform.protocol.media;

/**
 * Android Kafka 图片资源引用。
 *
 * @param sha256 Redis 原图内容的 SHA-256 小写十六进制摘要
 * @param sizeBytes Redis 原图字节数
 * @param mimetype Redis 原图 MIME 类型
 * @param transformProfile Android 图片规范化规则版本
 */
public record AndroidImageAssetRef(
        String sha256,
        int sizeBytes,
        String mimetype,
        String transformProfile
) {
}
