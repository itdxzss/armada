package com.armada.hyperlink.task.service;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 账号级 Redis TTL holder；数据库发送中计数是跨任务容量的最终安全边界。 */
@Component
public class HyperlinkAccountDispatchGuard {
    private static final Logger log = LoggerFactory.getLogger(HyperlinkAccountDispatchGuard.class);
    static final int MAX_IN_FLIGHT = 20;
    /** holder 的运维续租窗口；过期不改变数据库硬容量门禁。 */
    static final long HOLDER_TTL_MS = 600_000L;
    static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local expiresAt = tonumber(ARGV[2])
            local holder = ARGV[3]
            local capacity = tonumber(ARGV[4])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            if redis.call('ZSCORE', KEYS[1], holder) then
                redis.call('ZADD', KEYS[1], expiresAt, holder)
                redis.call('PEXPIRE', KEYS[1], ARGV[5])
                return 1
            end
            if redis.call('ZCARD', KEYS[1]) >= capacity then
                return 0
            end
            redis.call('ZADD', KEYS[1], expiresAt, holder)
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            return 1
            """, Long.class);
    static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            local removed = redis.call('ZREM', KEYS[1], ARGV[1])
            if redis.call('ZCARD', KEYS[1]) == 0 then
                redis.call('DEL', KEYS[1])
            end
            return removed
            """, Long.class);
    private static final String KEY_PREFIX = "armada:hyperlink:account-guard:";

    private final StringRedisTemplate redis;
    private final Clock clock;

    @Autowired
    public HyperlinkAccountDispatchGuard(
            @Qualifier("groupCreateIdempotencyRedisTemplate") StringRedisTemplate redis) {
        this(redis, Clock.systemUTC());
    }

    HyperlinkAccountDispatchGuard(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /**
     * 获取或续租账号 holder；同一 commandId 幂等续租，容量已满返回 false。
     *
     * @param accountId Armada 全局账号 ID
     * @param commandId recipient 的稳定命令 ID
     * @return true 表示 holder 已受保护，false 表示账号全局容量已满
     * @throws BusinessException Redis 不可用或返回值异常时抛 50311
     */
    public boolean tryAcquire(long accountId, String commandId) {
        if (commandId == null || commandId.isBlank()) {
            throw unavailable();
        }
        long now = clock.millis();
        try {
            Long result = redis.execute(
                    ACQUIRE_SCRIPT,
                    List.of(key(accountId)),
                    Long.toString(now),
                    Long.toString(now + HOLDER_TTL_MS),
                    commandId,
                    Integer.toString(MAX_IN_FLIGHT),
                    Long.toString(HOLDER_TTL_MS));
            if (Long.valueOf(1L).equals(result)) {
                return true;
            }
            if (Long.valueOf(0L).equals(result)) {
                return false;
            }
            throw unavailable();
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    /**
     * 续租必须成功；保护容量已满或 Redis 异常时失败关闭。
     *
     * @param accountId Armada 全局账号 ID
     * @param commandId recipient 的稳定命令 ID
     * @throws BusinessException holder 无法续租时抛 50311
     */
    public void renew(long accountId, String commandId) {
        if (!tryAcquire(accountId, commandId)) {
            throw unavailable();
        }
    }

    /**
     * 幂等释放指定 commandId 自己持有的账号槽位，不影响同账号其他命令。
     *
     * @param accountId Armada 全局账号 ID
     * @param commandId recipient 的稳定命令 ID
     * @throws BusinessException Redis 不可用或返回值异常时抛 50311
     */
    public void release(long accountId, String commandId) {
        if (commandId == null || commandId.isBlank()) {
            throw unavailable();
        }
        try {
            Long result = redis.execute(RELEASE_SCRIPT, List.of(key(accountId)), commandId);
            if (result == null) {
                throw unavailable();
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    /**
     * recipient 终态事务提交后释放 holder；回滚时保留，避免原发送仍在途却提前放开容量。
     *
     * @param accountId Armada 全局账号 ID
     * @param commandId recipient 的稳定命令 ID
     * @param taskId 超链任务 ID，仅用于脱敏日志定位
     * @param recipientId recipient ID，仅用于脱敏日志定位
     */
    public void releaseAfterCommit(
            long accountId, String commandId, long taskId, long recipientId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            release(accountId, commandId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            release(accountId, commandId);
                        } catch (BusinessException exception) {
                            log.error("hyperlink account holder release failed taskId={} recipientId={}",
                                    taskId, recipientId, exception);
                        }
                    }
                });
    }

    private String key(long accountId) {
        return KEY_PREFIX + "{account:" + accountId + "}:holders";
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.HYPERLINK_DISPATCH_GUARD_UNAVAILABLE);
    }
}
