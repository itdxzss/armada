package com.armada.platform.protocol.media;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 待写入共享 Redis 的租户级 Android 原图资源。
 *
 * <p>资源身份只由租户和原图 SHA-256 决定，不包含任务、群组、账号或命令。
 * {@code sourceBytes} 沿用消息模型的只读约定，不做每命令防御性复制。</p>
 */
public final class AndroidImageAsset {

    /** Android 当前支持的图片规范化规则版本。 */
    public static final String TRANSFORM_PROFILE = "marketing-image-v1";

    /** Redis namespace 内的营销图片逻辑前缀。 */
    private static final String LOGICAL_PREFIX = "marketing:image:v1:";

    /** SHA-256 摘要算法名称。 */
    private static final String SHA_256 = "SHA-256";

    /** 图片所属租户 ID。 */
    private final Long tenantId;

    /** 原图 SHA-256 小写十六进制摘要。 */
    private final String sha256;

    /** 原始图片字节，只读且不复制。 */
    private final byte[] sourceBytes;

    /** 原始图片 MIME 类型。 */
    private final String mimetype;

    private AndroidImageAsset(
            Long tenantId,
            String sha256,
            byte[] sourceBytes,
            String mimetype) {
        this.tenantId = tenantId;
        this.sha256 = sha256;
        this.sourceBytes = sourceBytes;
        this.mimetype = mimetype;
    }

    /**
     * 从营销消息原图创建稳定的租户级资源。
     *
     * <p>500KB 上限已在图片落库入口校验，此处只校验构造引用所需的完整性，不重复业务大小门禁。</p>
     *
     * @param tenantId 图片所属租户 ID
     * @param sourceBytes 原始图片字节，调用方后续不得修改
     * @param mimetype 原始图片 MIME 类型
     * @return 包含稳定 SHA-256 身份的图片资源
     * @throws IllegalArgumentException 当租户、图片或 MIME 类型缺失时抛出
     */
    public static AndroidImageAsset from(Long tenantId, byte[] sourceBytes, String mimetype) {
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (sourceBytes == null || sourceBytes.length == 0) {
            throw new IllegalArgumentException("image bytes are required");
        }
        if (mimetype == null || mimetype.isBlank()) {
            throw new IllegalArgumentException("image mimetype is required");
        }
        return new AndroidImageAsset(
                tenantId,
                sha256(sourceBytes),
                sourceBytes,
                mimetype.trim());
    }

    /**
     * 返回跨任务复用的租户级资源身份。
     *
     * @return tenantId 与 SHA-256 组成的身份
     */
    public String identity() {
        return tenantId + ":" + sha256;
    }

    /**
     * 创建 Kafka 使用的图片引用。
     *
     * @return 不包含 Redis 物理 Key 和图片内容的引用
     */
    public AndroidImageAssetRef reference() {
        return new AndroidImageAssetRef(
                sha256,
                sourceBytes.length,
                mimetype,
                TRANSFORM_PROFILE);
    }

    /**
     * 组合双方约定的 Redis 物理 Key。
     *
     * @param keyPrefix 与 Android 进程一致的全局 namespace
     * @return tenantId 与 SHA-256 隔离的物理 Key
     */
    public String redisKey(String keyPrefix) {
        return keyPrefix + LOGICAL_PREFIX + tenantId + ":" + sha256;
    }

    /**
     * 获取图片所属租户 ID。
     *
     * @return 租户 ID
     */
    public Long tenantId() {
        return tenantId;
    }

    /**
     * 获取原图 SHA-256 摘要。
     *
     * @return 小写十六进制摘要
     */
    public String sha256() {
        return sha256;
    }

    /**
     * 获取只读原始图片字节。
     *
     * @return 不复制的原图字节
     */
    public byte[] sourceBytes() {
        return sourceBytes;
    }

    /**
     * 获取原始图片 MIME 类型。
     *
     * @return MIME 类型
     */
    public String mimetype() {
        return mimetype;
    }

    private static String sha256(byte[] source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(SHA_256).digest(source));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
