package com.armada.account.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.armada.account.service.impl.AccountRegistrationLease;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** 验证生产同时存在认证与业务 Redis 时，注册锁明确使用业务实例。 */
class AccountRegistrationLeaseConfigurationTest {
    @Test
    void selectsBusinessRedisWhenAuthenticationRedisIsAlsoPresent() {
        StringRedisTemplate auth = mock(StringRedisTemplate.class);
        StringRedisTemplate business = mock(StringRedisTemplate.class);
        new ApplicationContextRunner()
                .withBean("authRedisTemplate", StringRedisTemplate.class, () -> auth)
                .withBean("groupCreateIdempotencyRedisTemplate", StringRedisTemplate.class, () -> business)
                .withUserConfiguration(AccountRegistrationLease.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AccountRegistrationLease.class);
                    assertThat(ReflectionTestUtils.getField(context.getBean(AccountRegistrationLease.class), "redis"))
                            .isSameAs(business);
                });
    }
}
