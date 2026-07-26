package com.armada.platform.protocol.idempotency;

import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** 使用 Redis 原子保存建群领取状态和首次成功结果。 */
public final class RedisGroupCreateIdempotencyStore implements GroupCreateIdempotencyStore {

    private static final DefaultRedisScript<Long> SAVE_SUCCESS_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
                        return 1
                    end
                    return 0
                    """, Long.class);

    private static final DefaultRedisScript<Long> CLEAR_PROCESSING_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        return redis.call('DEL', KEYS[1])
                    end
                    return 0
                    """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final Duration ttl;

    public RedisGroupCreateIdempotencyStore(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            String keyPrefix,
            Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.keyPrefix = keyPrefix;
        this.ttl = ttl;
    }

    @Override
    public Optional<GroupCreateIdempotencyRecord> find(String operationId) {
        String value = redis.opsForValue().get(key(operationId));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(
                    value, GroupCreateIdempotencyRecord.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("建群幂等记录 JSON 损坏", exception);
        }
    }

    @Override
    public boolean tryBegin(String operationId, String claimToken) {
        Boolean created = redis.opsForValue().setIfAbsent(
                key(operationId),
                serialize(GroupCreateIdempotencyRecord.processing(claimToken)),
                ttl);
        if (created == null) {
            throw new IllegalStateException("Redis 未返回建群幂等领取结果");
        }
        return created;
    }

    @Override
    public void saveSuccess(
            String operationId,
            String claimToken,
            GroupCreateResult result) {
        Long updated = redis.execute(
                SAVE_SUCCESS_SCRIPT,
                List.of(key(operationId)),
                serialize(GroupCreateIdempotencyRecord.processing(claimToken)),
                serialize(GroupCreateIdempotencyRecord.succeeded(claimToken, result)),
                Long.toString(ttl.toMillis()));
        if (!Long.valueOf(1L).equals(updated)) {
            throw new IllegalStateException("建群幂等领取已失效，不能保存成功结果");
        }
    }

    @Override
    public void clearProcessing(String operationId, String claimToken) {
        redis.execute(
                CLEAR_PROCESSING_SCRIPT,
                List.of(key(operationId)),
                serialize(GroupCreateIdempotencyRecord.processing(claimToken)));
    }

    private String key(String operationId) {
        return keyPrefix + operationId;
    }

    private String serialize(GroupCreateIdempotencyRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("建群幂等记录序列化失败", exception);
        }
    }
}
