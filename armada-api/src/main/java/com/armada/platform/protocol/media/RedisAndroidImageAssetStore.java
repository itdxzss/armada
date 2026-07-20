package com.armada.platform.protocol.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;

/**
 * 使用共享 Redis 保存 Android 营销图片原始二进制的实现。
 */
public final class RedisAndroidImageAssetStore implements AndroidImageAssetStore {

    /** 组件日志。 */
    private static final Logger log = LoggerFactory.getLogger(RedisAndroidImageAssetStore.class);

    /** Armada 实际使用图片后保持原图 24 小时可读。 */
    private static final Duration ASSET_TTL = Duration.ofHours(24);

    /** 处理并发 SET NX 竞争时允许的最大确认次数。 */
    private static final int MAX_ENSURE_ATTEMPTS = 2;

    /** 耗时从纳秒换算到微秒的除数。 */
    private static final long NANOS_PER_MICROSECOND = 1_000L;

    /** 二进制 Redis 操作模板。 */
    private final RedisTemplate<String, byte[]> redis;

    /** 与 Android 进程一致的 Redis 全局 Key 前缀。 */
    private final String keyPrefix;

    /**
     * 创建 Android 原图 Redis 存储。
     *
     * @param redis 使用字符串 Key、原始字节 Value 的 Redis 模板
     * @param keyPrefix 与 Android 进程一致的全局 Key 前缀
     */
    public RedisAndroidImageAssetStore(
            RedisTemplate<String, byte[]> redis,
            String keyPrefix) {
        this.redis = redis;
        this.keyPrefix = keyPrefix.trim();
    }

    /**
     * 保证原图存在并拥有完整 24 小时 TTL。
     *
     * <p>命中时只续期；未命中时使用 SET NX 写原始二进制。并发写入竞争后再次续期，
     * 无法确认可用性时阻止 outbox 落库。</p>
     *
     * @param asset 待缓存的租户级原图资源
     */
    @Override
    public void ensure(AndroidImageAsset asset) {
        String key = asset.redisKey(keyPrefix);
        long startedAt = System.nanoTime();
        try {
            for (int attempt = 0; attempt < MAX_ENSURE_ATTEMPTS; attempt++) {
                if (Boolean.TRUE.equals(redis.expire(key, ASSET_TTL))) {
                    log.debug(
                            "Android image Redis TTL refreshed tenantId={} shaPrefix={} elapsedMicros={}",
                            asset.tenantId(),
                            shaPrefix(asset),
                            elapsedMicros(startedAt));
                    return;
                }
                if (Boolean.TRUE.equals(redis.opsForValue()
                        .setIfAbsent(key, asset.sourceBytes(), ASSET_TTL))) {
                    log.info(
                            "Android image cached tenantId={} shaPrefix={} sizeBytes={} elapsedMicros={}",
                            asset.tenantId(),
                            shaPrefix(asset),
                            asset.sourceBytes().length,
                            elapsedMicros(startedAt));
                    return;
                }
            }
            throw new IllegalStateException(
                    "ensure Android image asset: Redis key changed repeatedly");
        } catch (RuntimeException exception) {
            log.warn(
                    "Android image Redis ensure failed tenantId={} shaPrefix={} sizeBytes={}",
                    asset.tenantId(),
                    shaPrefix(asset),
                    asset.sourceBytes().length,
                    exception);
            throw exception;
        }
    }

    private static String shaPrefix(AndroidImageAsset asset) {
        return asset.sha256().substring(0, 8);
    }

    private static long elapsedMicros(long startedAt) {
        return (System.nanoTime() - startedAt) / NANOS_PER_MICROSECOND;
    }
}
