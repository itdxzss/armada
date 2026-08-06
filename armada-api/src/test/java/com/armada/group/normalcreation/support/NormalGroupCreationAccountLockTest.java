package com.armada.group.normalcreation.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.redis.core.StringRedisTemplate;

class NormalGroupCreationAccountLockTest {

    @Test
    void verifiesOwnershipAndReleasesEveryAccountBeforeReturningSuccess() {
        StringRedisTemplate redis = redisReturning(1L, 1L, 2L);
        NormalGroupCreationAccountLock lock =
                new NormalGroupCreationAccountLock(redis, "test:lock:", 10_000L);

        String result = lock.callWithLocks(7L, List.of(2L, 1L), () -> "ok");

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void reportsLockLostWhenFinalOwnershipCheckFails() {
        StringRedisTemplate redis = redisReturning(1L, 0L, 1L);
        NormalGroupCreationAccountLock lock =
                new NormalGroupCreationAccountLock(redis, "test:lock:", 10_000L);

        assertThatThrownBy(() -> lock.callWithLocks(7L, List.of(1L), () -> "ok"))
                .isInstanceOf(NormalGroupCreationLockLostException.class)
                .hasMessageContaining("失去所有权");
    }

    @Test
    void reportsLockLostBeforeRetryableActionFailureWhenBothOccur() {
        StringRedisTemplate redis = redisReturning(1L, 0L, 1L);
        NormalGroupCreationAccountLock lock =
                new NormalGroupCreationAccountLock(redis, "test:lock:", 10_000L);
        NormalGroupCreationRetryableException actionFailure =
                new NormalGroupCreationRetryableException("协议暂时不可用");

        assertThatThrownBy(() -> lock.callWithLocks(7L, List.of(1L), () -> {
                    throw actionFailure;
                }))
                .isInstanceOf(NormalGroupCreationLockLostException.class)
                .hasMessageContaining("失去所有权")
                .satisfies(ex -> assertThat(ex.getSuppressed()).containsExactly(actionFailure));
    }

    private static StringRedisTemplate redisReturning(Long... results) {
        AtomicInteger index = new AtomicInteger();
        return mock(StringRedisTemplate.class, invocation -> {
            if ("execute".equals(invocation.getMethod().getName())) {
                int current = index.getAndIncrement();
                return results[Math.min(current, results.length - 1)];
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }
}
