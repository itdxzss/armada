package com.armada.group.normalcreation.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.group.normalcreation.mapper.NormalGroupCreationMapper;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.redis.core.StringRedisTemplate;

class NormalGroupCreationAdmissionGuardTest {

    @Test
    void rejectsWhenActiveTaskCapacityIsFull() {
        NormalGroupCreationMapper mapper = mock(NormalGroupCreationMapper.class);
        allowAdmissionLock(mapper);
        StringRedisTemplate redis = redisReturning(1L);
        when(mapper.selectActiveGroupCountsForUpdate())
                .thenReturn(Collections.nCopies(20, 5));
        NormalGroupCreationAdmissionGuard guard = guard(mapper, redis);

        assertThatThrownBy(() -> guard.lockAndCheckCapacity(7L, 1))
                .hasMessageContaining("活动建群任务已达上限");
        verifyNoInteractions(redis);
    }

    @Test
    void rejectsWhenRequestedGroupsWouldExceedInFlightCapacity() {
        NormalGroupCreationMapper mapper = mock(NormalGroupCreationMapper.class);
        allowAdmissionLock(mapper);
        StringRedisTemplate redis = redisReturning(1L);
        when(mapper.selectActiveGroupCountsForUpdate()).thenReturn(List.of(2_450, 2_450));
        NormalGroupCreationAdmissionGuard guard = guard(mapper, redis);

        assertThatThrownBy(() -> guard.lockAndCheckCapacity(7L, 101))
                .hasMessageContaining("在途群数量将超过上限");
        verifyNoInteractions(redis);
    }

    @Test
    void rejectsWhenTenantSubmissionRateIsExceeded() {
        NormalGroupCreationMapper mapper = mock(NormalGroupCreationMapper.class);
        NormalGroupCreationAdmissionGuard guard = guard(mapper, redisReturning(-1L));

        assertThatThrownBy(() -> guard.checkRate(7L, 8L))
                .hasMessageContaining("租户新建普群提交过于频繁");
    }

    @Test
    void admitsRequestWithinCapacityAndRateLimits() {
        NormalGroupCreationMapper mapper = mock(NormalGroupCreationMapper.class);
        allowAdmissionLock(mapper);
        when(mapper.selectActiveGroupCountsForUpdate()).thenReturn(List.of(10));
        NormalGroupCreationAdmissionGuard guard = guard(mapper, redisReturning(1L));

        assertThatCode(() -> {
            guard.checkRate(7L, 8L);
            guard.lockAndCheckCapacity(7L, 100);
        }).doesNotThrowAnyException();
    }

    @Test
    void rateLimitKeysUseTheSameRedisClusterHashSlot() {
        NormalGroupCreationMapper mapper = mock(NormalGroupCreationMapper.class);
        AtomicReference<List<String>> capturedKeys = new AtomicReference<>();
        StringRedisTemplate redis = mock(StringRedisTemplate.class, invocation -> {
            if ("execute".equals(invocation.getMethod().getName())) {
                capturedKeys.set(invocation.getArgument(1));
                return 1L;
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });

        guard(mapper, redis).checkRate(7L, 8L);

        assertThat(capturedKeys.get())
                .hasSize(2)
                .allMatch(key -> key.contains("{tenant:7}"));
    }

    @Test
    void rejectsWhenTenantAdmissionLockCannotBeAcquired() {
        NormalGroupCreationMapper mapper = mock(NormalGroupCreationMapper.class);
        StringRedisTemplate redis = redisReturning(1L);

        assertThatThrownBy(() -> guard(mapper, redis).lockAndCheckCapacity(7L, 1))
                .hasMessageContaining("准入服务暂不可用");
        verifyNoInteractions(redis);
    }

    private static void allowAdmissionLock(NormalGroupCreationMapper mapper) {
        when(mapper.lockAdmission(anyLong())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static NormalGroupCreationAdmissionGuard guard(
            NormalGroupCreationMapper mapper, StringRedisTemplate redis) {
        return new NormalGroupCreationAdmissionGuard(
                mapper, redis, "test:admission:", 20, 5_000, 10, 5);
    }

    private static StringRedisTemplate redisReturning(Long result) {
        return mock(StringRedisTemplate.class, invocation -> {
            if ("execute".equals(invocation.getMethod().getName())) {
                return result;
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }
}
