package com.armada.group.normalcreation.support;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 新建普群协议调用使用的短时账号级分布式锁。 */
@Component
public class NormalGroupCreationAccountLock {

    private static final Logger log =
            LoggerFactory.getLogger(NormalGroupCreationAccountLock.class);

    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT =
            new DefaultRedisScript<>("""
                    for _, key in ipairs(KEYS) do
                        if redis.call('EXISTS', key) == 1 then
                            return 0
                        end
                    end
                    for _, key in ipairs(KEYS) do
                        redis.call('PSETEX', key, ARGV[2], ARGV[1])
                    end
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
            new DefaultRedisScript<>("""
                    local released = 0
                    for _, key in ipairs(KEYS) do
                        if redis.call('GET', key) == ARGV[1] then
                            released = released + redis.call('DEL', key)
                        end
                    end
                    return released
                    """, Long.class);

    private static final DefaultRedisScript<Long> RENEW_SCRIPT =
            new DefaultRedisScript<>("""
                    for _, key in ipairs(KEYS) do
                        if redis.call('GET', key) ~= ARGV[1] then
                            return 0
                        end
                    end
                    for _, key in ipairs(KEYS) do
                        redis.call('PEXPIRE', key, ARGV[2])
                    end
                    return 1
                    """, Long.class);

    private static final ScheduledExecutorService RENEWAL_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "normal-group-account-lock-renewal");
                thread.setDaemon(true);
                return thread;
            });

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final long ttlMs;

    public NormalGroupCreationAccountLock(
            @Qualifier("groupCreateIdempotencyRedisTemplate") StringRedisTemplate redis,
            @Value("${armada.normal-group-creation.account-lock-key-prefix:armada:normal-group-creation:account-lock:}")
            String keyPrefix,
            @Value("${armada.normal-group-creation.account-lock-ttl-ms:60000}") long ttlMs) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;
        this.ttlMs = Math.max(ttlMs, 10_000L);
    }

    /**
     * 对同租户账号按稳定顺序加锁后执行协议调用。
     *
     * <p>联系人阶段每次只锁建群人和当前成员，避免一次持有上千个 Redis 键。</p>
     */
    public void runWithLocks(Long tenantId, Collection<Long> accountIds, Runnable action) {
        callWithLocks(tenantId, accountIds, () -> {
            action.run();
            return null;
        });
    }

    /** 对同租户账号加锁后执行并返回结果。 */
    public <T> T callWithLocks(
            Long tenantId, Collection<Long> accountIds, Supplier<T> action) {
        if (tenantId == null || tenantId <= 0 || accountIds == null || accountIds.isEmpty()) {
            throw new IllegalArgumentException("账号锁参数非法");
        }
        List<String> keys = accountIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .sorted()
                .map(id -> keyPrefix + "{" + tenantId + "}:" + id)
                .toList();
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("账号锁账号为空");
        }
        String token = UUID.randomUUID().toString();
        try {
            Long acquired = redis.execute(
                    ACQUIRE_SCRIPT, keys, token, Long.toString(ttlMs));
            if (!Long.valueOf(1L).equals(acquired)) {
                throw new NormalGroupCreationRetryableException("账号正在执行其他互斥操作");
            }
        } catch (NormalGroupCreationRetryableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new NormalGroupCreationRetryableException("账号互斥锁服务暂不可用", ex);
        }
        AtomicBoolean lockValid = new AtomicBoolean(true);
        AtomicBoolean renewalActive = new AtomicBoolean(true);
        Object renewalMonitor = new Object();
        long renewalIntervalMs = Math.max(1_000L, ttlMs / 3L);
        ScheduledFuture<?> renewal = RENEWAL_EXECUTOR.scheduleAtFixedRate(
                () -> renew(tenantId, keys, token, lockValid, renewalActive, renewalMonitor),
                renewalIntervalMs, renewalIntervalMs, TimeUnit.MILLISECONDS);
        T result = null;
        RuntimeException actionFailure = null;
        try {
            result = action.get();
        } catch (RuntimeException ex) {
            actionFailure = ex;
        }

        boolean ownedAtCompletion = stopRenewalAndVerifyOwnership(
                tenantId, keys, token, lockValid, renewalActive, renewalMonitor, renewal);
        Long released = release(tenantId, keys, token);
        if (!ownedAtCompletion || !Long.valueOf(keys.size()).equals(released)) {
            NormalGroupCreationLockLostException lockLost =
                    new NormalGroupCreationLockLostException(
                    "账号互斥锁在协议操作完成前失去所有权");
            if (actionFailure != null) {
                lockLost.addSuppressed(actionFailure);
            }
            throw lockLost;
        }
        if (actionFailure != null) {
            throw actionFailure;
        }
        return result;
    }

    private void renew(
            Long tenantId,
            List<String> keys,
            String token,
            AtomicBoolean lockValid,
            AtomicBoolean renewalActive,
            Object renewalMonitor) {
        synchronized (renewalMonitor) {
            if (!renewalActive.get() || !lockValid.get()) {
                return;
            }
            try {
                Long renewed = redis.execute(RENEW_SCRIPT, keys, token, Long.toString(ttlMs));
                if (!Long.valueOf(1L).equals(renewed)) {
                    lockValid.set(false);
                    log.error("新建普群账号互斥锁续租时已失去所有权 tenantId={} accountCount={}",
                            tenantId, keys.size());
                }
            } catch (RuntimeException ex) {
                lockValid.set(false);
                log.error("新建普群账号互斥锁续租失败 tenantId={} accountCount={}",
                        tenantId, keys.size(), ex);
            }
        }
    }

    private boolean stopRenewalAndVerifyOwnership(
            Long tenantId,
            List<String> keys,
            String token,
            AtomicBoolean lockValid,
            AtomicBoolean renewalActive,
            Object renewalMonitor,
            ScheduledFuture<?> renewal) {
        renewal.cancel(false);
        synchronized (renewalMonitor) {
            renewalActive.set(false);
            if (!lockValid.get()) {
                return false;
            }
            try {
                Long verified = redis.execute(RENEW_SCRIPT, keys, token, Long.toString(ttlMs));
                return Long.valueOf(1L).equals(verified);
            } catch (RuntimeException ex) {
                log.error("新建普群账号互斥锁完成校验失败 tenantId={} accountCount={}",
                        tenantId, keys.size(), ex);
                return false;
            }
        }
    }

    private Long release(Long tenantId, List<String> keys, String token) {
        try {
            return redis.execute(RELEASE_SCRIPT, keys, token);
        } catch (RuntimeException ex) {
            // 锁有 TTL；释放失败记录后由调用方按锁丢失收敛，不覆盖 action 的原始异常。
            log.warn("新建普群账号互斥锁释放失败 tenantId={} accountCount={}",
                    tenantId, keys.size(), ex);
            return null;
        }
    }
}
