package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.GroupLinkHealthReportedEvent;
import com.armada.group.model.entity.GroupLinkHealth;
import com.armada.group.model.enums.GroupLinkHealthStatus;
import com.armada.group.service.impl.GroupCurrentInvitePersistence;
import com.armada.group.service.impl.GroupLinkHealthReportServiceImpl;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 群健康事件只写当前群资料的单测。 */
@ExtendWith(MockitoExtension.class)
class GroupLinkHealthReportServiceImplTest {

    @Mock private GroupLinkMapper groupLinkMapper;
    @Mock private AccountMapper accountMapper;
    @Mock private GroupCurrentInvitePersistence currentInvitePersistence;

    private GroupLinkHealthReportServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        DataScopeContext.clear();
        service = new GroupLinkHealthReportServiceImpl(
                groupLinkMapper, accountMapper, currentInvitePersistence);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        DataScopeContext.clear();
    }

    @Test
    void healthyEventWritesAvailableCurrentProfileAndRestoresTenant() {
        when(accountMapper.selectActiveByProtocolAccountId("acc"))
                .thenReturn(account(501L));
        when(groupLinkMapper.selectActiveIdByGroupJidAndId("1203630health@g.us", 200L, 501L))
                .thenReturn(200L);
        when(currentInvitePersistence.findHealth("1203630health@g.us"))
                .thenReturn(currentHealth(44, 3));

        Optional<Long> result = service.applyHealthReported(event(
                9L, 200L, "1203630health@g.us", "HEALTHY", 55, null));

        assertThat(result).contains(200L);
        ArgumentCaptor<GroupLinkHealth> row = ArgumentCaptor.forClass(GroupLinkHealth.class);
        verify(currentInvitePersistence).applyHealth(
                org.mockito.ArgumentMatchers.eq("1203630health@g.us"), row.capture());
        assertThat(row.getValue().getHealthStatus())
                .isEqualTo(GroupLinkHealthStatus.AVAILABLE.code());
        assertThat(row.getValue().getCurrentCount()).isEqualTo(55);
        assertThat(row.getValue().getHealthFailureCount()).isZero();
        assertThat(TenantContext.get()).isNull();
        assertThat(DataScopeContext.current()).isEmpty();
    }

    @Test
    void errorPreservesCurrentCountAndIncrementsFailureCount() {
        when(accountMapper.selectActiveByProtocolAccountId("acc"))
                .thenReturn(account(501L));
        when(groupLinkMapper.selectActiveIdByGroupJidAndId("1203630error@g.us", 201L, 501L))
                .thenReturn(201L);
        when(currentInvitePersistence.findHealth("1203630error@g.us"))
                .thenReturn(currentHealth(66, 2));

        service.applyHealthReported(event(
                10L, 201L, "1203630error@g.us", "ERROR", null,
                "GROUP_METADATA_FAILED"));

        ArgumentCaptor<GroupLinkHealth> row = ArgumentCaptor.forClass(GroupLinkHealth.class);
        verify(currentInvitePersistence).applyHealth(
                org.mockito.ArgumentMatchers.eq("1203630error@g.us"), row.capture());
        assertThat(row.getValue().getHealthStatus())
                .isEqualTo(GroupLinkHealthStatus.UNAVAILABLE.code());
        assertThat(row.getValue().getCurrentCount()).isEqualTo(66);
        assertThat(row.getValue().getHealthFailureCount()).isEqualTo(3);
    }

    @Test
    void unknownGroupSkipsCurrentHealthWrite() {
        when(accountMapper.selectActiveByProtocolAccountId("acc")).thenReturn(account(1L));
        when(groupLinkMapper.selectActiveIdByGroupJid("1203630missing@g.us", 1L))
                .thenReturn(null);

        assertThat(service.applyHealthReported(event(
                12L, null, "1203630missing@g.us", "BANNED", null,
                "CHAT_TERMINATED"))).isEmpty();

        verifyNoInteractions(currentInvitePersistence);
    }

    @Test
    void conflictingHandleAndGroupJidAreRejected() {
        when(accountMapper.selectActiveByProtocolAccountId("acc"))
                .thenReturn(account(501L));
        assertThatThrownBy(() -> service.applyHealthReported(event(
                12L, 204L, "1203630mismatch@g.us", "BANNED", null,
                "CHAT_SUSPENDED")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("群链接健康事件 groupLinkId 与 groupJid 不一致");
        verify(groupLinkMapper).selectActiveIdByGroupJidAndId(
                "1203630mismatch@g.us", 204L, 501L);
    }

    @Test
    void historicalUnownedExecutionAccountIsRejected() {
        when(accountMapper.selectActiveByProtocolAccountId("acc"))
                .thenReturn(account(null));

        assertThatThrownBy(() -> service.applyHealthReported(event(
                12L, 204L, "1203630mismatch@g.us", "BANNED", null,
                "CHAT_SUSPENDED")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.ACCESS_DENIED.code());

        verifyNoInteractions(currentInvitePersistence);
        assertThat(DataScopeContext.current()).isEmpty();
    }

    private static GroupLinkHealthReportedEvent event(
            Long tenantId,
            Long groupLinkId,
            String groupJid,
            String health,
            Integer memberCount,
            String errorCode) {
        return new GroupLinkHealthReportedEvent(
                tenantId, groupLinkId, groupJid, health, memberCount,
                1_782_712_801_000L, errorCode, "acc", "evt");
    }

    private static GroupLinkHealth currentHealth(Integer count, Integer failures) {
        GroupLinkHealth current = new GroupLinkHealth();
        current.setCurrentCount(count);
        current.setHealthFailureCount(failures);
        return current;
    }

    private static Account account(Long ownerUserId) {
        Account account = new Account();
        account.setOwnerUserId(ownerUserId);
        account.setProtocolAccountId("acc");
        return account;
    }
}
