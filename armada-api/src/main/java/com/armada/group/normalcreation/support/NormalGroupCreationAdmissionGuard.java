package com.armada.group.normalcreation.support;

import com.armada.group.normalcreation.mapper.NormalGroupCreationMapper;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 新建普群入口的租户容量与提交频率准入。 */
@Component
public class NormalGroupCreationAdmissionGuard {

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT =
            new DefaultRedisScript<>("""
                    local tenantCount = tonumber(redis.call('GET', KEYS[1]) or '0')
                    local userCount = tonumber(redis.call('GET', KEYS[2]) or '0')
                    if tenantCount >= tonumber(ARGV[1]) then
                        return -1
                    end
                    if userCount >= tonumber(ARGV[2]) then
                        return -2
                    end
                    tenantCount = redis.call('INCR', KEYS[1])
                    userCount = redis.call('INCR', KEYS[2])
                    if tenantCount == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[3]) end
                    if userCount == 1 then redis.call('PEXPIRE', KEYS[2], ARGV[3]) end
                    return 1
                    """, Long.class);

    private final NormalGroupCreationMapper mapper;
    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final int maxActiveTasks;
    private final int maxInFlightGroups;
    private final int tenantRequestsPerMinute;
    private final int userRequestsPerMinute;

    public NormalGroupCreationAdmissionGuard(
            NormalGroupCreationMapper mapper,
            @Qualifier("groupCreateIdempotencyRedisTemplate") StringRedisTemplate redis,
            @Value("${armada.normal-group-creation.admission.key-prefix:armada:normal-group-creation:admission:}")
            String keyPrefix,
            @Value("${armada.normal-group-creation.admission.max-active-tasks:20}")
            int maxActiveTasks,
            @Value("${armada.normal-group-creation.admission.max-in-flight-groups:5000}")
            int maxInFlightGroups,
            @Value("${armada.normal-group-creation.admission.tenant-requests-per-minute:10}")
            int tenantRequestsPerMinute,
            @Value("${armada.normal-group-creation.admission.user-requests-per-minute:5}")
            int userRequestsPerMinute) {
        this.mapper = mapper;
        this.redis = redis;
        this.keyPrefix = keyPrefix;
        this.maxActiveTasks = Math.max(maxActiveTasks, 1);
        this.maxInFlightGroups = Math.max(maxInFlightGroups, 1);
        this.tenantRequestsPerMinute = Math.max(tenantRequestsPerMinute, 1);
        this.userRequestsPerMinute = Math.max(userRequestsPerMinute, 1);
    }

    /** 在账号候选查询和数据库准入锁之前执行；幂等命中不进入本方法。 */
    public void checkRate(long tenantId, long userId) {
        long minute = Instant.now().getEpochSecond() / 60L;
        String tenantSlot = "{tenant:" + tenantId + "}";
        List<String> keys = List.of(
                keyPrefix + tenantSlot + ":minute:" + minute,
                keyPrefix + tenantSlot + ":user:" + userId + ":minute:" + minute);
        try {
            Long result = redis.execute(
                    RATE_LIMIT_SCRIPT,
                    keys,
                    Integer.toString(tenantRequestsPerMinute),
                    Integer.toString(userRequestsPerMinute),
                    "120000");
            if (Long.valueOf(-1L).equals(result)) {
                throw limited("当前租户新建普群提交过于频繁，请稍后再试");
            }
            if (Long.valueOf(-2L).equals(result)) {
                throw limited("当前用户新建普群提交过于频繁，请稍后再试");
            }
            if (!Long.valueOf(1L).equals(result)) {
                throw unavailable();
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw unavailable();
        }
    }

    /** 在冻结数据落库前取得租户行锁，并原子检查活动容量。 */
    public void lockAndCheckCapacity(long tenantId, int requestedGroups) {
        long now = System.currentTimeMillis();
        mapper.ensureAdmissionLock(tenantId, now);
        Long lockedTenantId = mapper.lockAdmission(tenantId);
        if (lockedTenantId == null || lockedTenantId != tenantId) {
            throw unavailable();
        }
        List<Integer> activeGroupCounts = mapper.selectActiveGroupCountsForUpdate();
        long activeTasks = activeGroupCounts.size();
        long inFlightGroups = activeGroupCounts.stream()
                .mapToLong(Integer::longValue)
                .sum();
        if (activeTasks >= maxActiveTasks) {
            throw limited("当前租户活动建群任务已达上限 " + maxActiveTasks + " 个，请等待任务完成");
        }
        if (inFlightGroups + requestedGroups > maxInFlightGroups) {
            throw limited("当前租户在途群数量将超过上限 " + maxInFlightGroups + " 个，请拆分或稍后提交");
        }
    }

    private static BusinessException limited(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    private static BusinessException unavailable() {
        return new BusinessException(
                ErrorCode.AUTH_SERVICE_UNAVAILABLE, "新建普群准入服务暂不可用，请稍后重试");
    }
}
