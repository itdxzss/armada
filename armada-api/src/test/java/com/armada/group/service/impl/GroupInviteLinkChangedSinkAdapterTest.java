package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.group.model.dto.GroupInviteLinkObservation;
import com.armada.group.mapper.GroupMetadataSyncTaskMapper;
import com.armada.group.service.GroupInviteLinkService;
import com.armada.platform.kafka.consumer.group.ProtocolGroupInviteLinkChangedEvent;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GroupInviteLinkChangedSinkAdapterTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        DataScopeContext.clear();
    }

    @Test
    void handlesEventInsideTenantContextAndRestoresIt() {
        GroupInviteLinkService service = mock(GroupInviteLinkService.class);
        AccountMapper accountMapper = mock(AccountMapper.class);
        when(accountMapper.selectActiveById(901L)).thenReturn(account("acc-901", 501L));
        AtomicReference<Long> observedTenant = new AtomicReference<>();
        AtomicReference<GroupInviteLinkObservation> observedEvent = new AtomicReference<>();
        doAnswer(invocation -> {
            observedTenant.set(TenantContext.get());
            assertThat(DataScopeContext.requireCurrent().actorUserId()).isEqualTo(501L);
            observedEvent.set(invocation.getArgument(0));
            return null;
        }).when(service).applyCurrentInvite(any());
        GroupInviteLinkChangedSinkAdapter adapter =
                new GroupInviteLinkChangedSinkAdapter(
                        service,
                        mock(GroupMetadataSyncTaskMapper.class),
                        mock(com.armada.group.mapper.GroupBatchTaskItemMapper.class),
                        accountMapper);

        adapter.handleInviteLinkChanged(new ProtocolGroupInviteLinkChangedEvent(
                "evt-1", 7L, 901L, "acc-901", "ANDROID",
                "120363group@g.us", "NewInviteCode_2026", null,
                "wgp2_notification", 1786341600000L, "worker", null));

        assertThat(observedTenant.get()).isEqualTo(7L);
        assertThat(observedEvent.get()).isEqualTo(new GroupInviteLinkObservation(
                "evt-1", null, "120363group@g.us", "NewInviteCode_2026",
                ProtocolBackend.ANDROID, "wgp2_notification", 1786341600000L));
        assertThat(TenantContext.get()).isNull();
        assertThat(DataScopeContext.current()).isEmpty();
    }

    @Test
    void staleProtocolBindingDoesNotWritePrivateGroupFacts() {
        GroupInviteLinkService service = mock(GroupInviteLinkService.class);
        AccountMapper accountMapper = mock(AccountMapper.class);
        when(accountMapper.selectActiveById(901L)).thenReturn(account("acc-current", 501L));
        GroupInviteLinkChangedSinkAdapter adapter = new GroupInviteLinkChangedSinkAdapter(
                service,
                mock(GroupMetadataSyncTaskMapper.class),
                mock(com.armada.group.mapper.GroupBatchTaskItemMapper.class),
                accountMapper);

        adapter.handleInviteLinkChanged(event());

        verify(service, never()).applyCurrentInvite(any());
        assertThat(DataScopeContext.current()).isEmpty();
    }

    @Test
    void historicalUnownedAccountIsRejected() {
        GroupInviteLinkService service = mock(GroupInviteLinkService.class);
        AccountMapper accountMapper = mock(AccountMapper.class);
        when(accountMapper.selectActiveById(901L)).thenReturn(account("acc-901", null));
        GroupInviteLinkChangedSinkAdapter adapter = new GroupInviteLinkChangedSinkAdapter(
                service,
                mock(GroupMetadataSyncTaskMapper.class),
                mock(com.armada.group.mapper.GroupBatchTaskItemMapper.class),
                accountMapper);

        assertThatThrownBy(() -> adapter.handleInviteLinkChanged(event()))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.ACCESS_DENIED.code());

        verify(service, never()).applyCurrentInvite(any());
        assertThat(DataScopeContext.current()).isEmpty();
    }

    private static ProtocolGroupInviteLinkChangedEvent event() {
        return new ProtocolGroupInviteLinkChangedEvent(
                "evt-1", 7L, 901L, "acc-901", "ANDROID",
                "120363group@g.us", "NewInviteCode_2026", null,
                "wgp2_notification", 1786341600000L, "worker", null);
    }

    private static Account account(String protocolAccountId, Long ownerUserId) {
        Account account = new Account();
        account.setId(901L);
        account.setOwnerUserId(ownerUserId);
        account.setProtocolAccountId(protocolAccountId);
        return account;
    }
}
