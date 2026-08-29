package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.armada.account.mapper.AccountProfileMapper;
import com.armada.account.service.AccountProfileService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 账号画像写入缝的租户、取值和事实时间校验测试。 */
class AccountProfileServiceTest {

    private static final long NOW = 2_000_000_000_000L;

    private final AccountProfileMapper mapper = Mockito.mock(AccountProfileMapper.class);
    private final AccountProfileService service = new AccountProfileServiceImpl(
            mapper, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void writesEveryFactWithTenantAndServerWriteTime() {
        TenantContext.set(7L);

        service.updateFriendCount(1, 10, 100);
        service.updateGroupInviteAllowed(1, true, 200);
        service.updateRotationStatus(1, 2, 300);
        service.initializeRegistration(1, 400, 2);
        service.updateMarketingSource(1, 4, 500);

        verify(mapper).upsertFriendCount(7, 1, 10, 100, NOW);
        verify(mapper).upsertGroupInviteAllowed(7, 1, true, 200, NOW);
        verify(mapper).upsertRotationStatus(7, 1, 2, 300, NOW);
        verify(mapper).initializeRegistration(7, 1, 400, 2, NOW);
        verify(mapper).upsertMarketingSource(7, 1, 4, 500, NOW);
    }

    @Test
    void rejectsMissingTenantAndInvalidFactsBeforeMapper() {
        assertThatThrownBy(() -> service.updateFriendCount(1, 10, 100))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("租户");
        TenantContext.set(7L);
        assertThatThrownBy(() -> service.updateFriendCount(1, -1, 100))
                .hasMessageContaining("好友数");
        assertThatThrownBy(() -> service.updateRotationStatus(1, 4, 100))
                .hasMessageContaining("轮号状态");
        assertThatThrownBy(() -> service.initializeRegistration(1, 100, 4))
                .hasMessageContaining("注册时间来源");
        assertThatThrownBy(() -> service.updateMarketingSource(1, 5, 100))
                .hasMessageContaining("营销来源");
        assertThatThrownBy(() -> service.updateGroupInviteAllowed(1, true, -1))
                .hasMessageContaining("事实时间");
        verifyNoInteractions(mapper);
    }
}
