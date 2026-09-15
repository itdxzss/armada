package com.armada.account.service.impl;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** 跨实例串行推进接码注册，令牌比较释放；数据库租约继续防止旧worker写入。 */
@Service
public class AccountRegistrationLease {
    /** 单步最多涉及数个有界HTTP调用，锁时限大于其配置上限。 */
    public static final Duration TTL = Duration.ofMinutes(3);
    /** 服务端专属调度互斥键。 */
    private static final String KEY = "armada:account-registration:tick";
    /** 只删除本次持有的锁，避免误删后续worker锁。 */
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    /** 复用当前部署的Redis连接。 */
    private final StringRedisTemplate redis;
    /** 明确使用业务互斥Redis，避免与认证Redis实例产生装配歧义。 */
    public AccountRegistrationLease(@Qualifier("groupCreateIdempotencyRedisTemplate") StringRedisTemplate redis) { this.redis = redis; }
    /** @return 已取得的令牌；未取得返回空字符串，不执行外部操作 */
    public String acquire() {
        String token = UUID.randomUUID().toString();
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(KEY, token, TTL)) ? token : "";
    }
    /** @param token 本轮令牌；只有仍持有者能够删除 */
    public void release(String token) { redis.execute(RELEASE, List.of(KEY), token); }
}
