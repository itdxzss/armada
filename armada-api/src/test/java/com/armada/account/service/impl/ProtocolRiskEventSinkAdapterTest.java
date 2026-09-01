package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.service.AccountOperationRestrictionService;
import com.armada.platform.kafka.consumer.account.ProtocolAccountRestrictedEvent;
import com.armada.platform.protocol.risk.ProtocolRiskResultMetadata;
import com.armada.platform.protocol.risk.mapper.ProtocolRiskEventMapper;
import com.armada.platform.protocol.risk.model.ProtocolRiskEvent;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProtocolRiskEventSinkAdapterTest {

    private final ProtocolRiskEventMapper eventMapper = mock(ProtocolRiskEventMapper.class);
    private final AccountMapper accountMapper = mock(AccountMapper.class);
    private final AccountOperationRestrictionService restrictionService =
            mock(AccountOperationRestrictionService.class);
    private final ProtocolRiskEventSinkAdapter adapter = new ProtocolRiskEventSinkAdapter(
            eventMapper, accountMapper, restrictionService);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void recordsMessageRateLimitAtOperationScopeWithoutMutingTheAccount() {
        when(accountMapper.selectActiveByProtocolAccountId("acc-17"))
                .thenReturn(account(17L, "ANDROID"));

        adapter.handleResult(result(
                "evt-rate", "message.send_result_reported", "MESSAGE_SEND",
                17L, "acc-17", "ANDROID", "hyperlink_task", 8L, 9L,
                "hl:7:8:9", null, "PRIVATE", null, "429",
                "RATE_LIMITED", "slow down", 1_000L));

        ArgumentCaptor<ProtocolRiskEvent> captor = ArgumentCaptor.forClass(ProtocolRiskEvent.class);
        verify(eventMapper).insertIdempotent(captor.capture());
        ProtocolRiskEvent row = captor.getValue();
        assertThat(row.getSignalCode()).isEqualTo("RATE_LIMITED");
        assertThat(row.getScopeType()).isEqualTo("OPERATION");
        assertThat(row.getOperationType()).isEqualTo("MESSAGE_SEND");
        assertThat(row.getAccountId()).isEqualTo(17L);
        assertThat(row.getBusinessType()).isEqualTo("hyperlink_task");
        assertThat(row.getBusinessId()).isEqualTo(8L);
        assertThat(row.getBusinessItemId()).isEqualTo(9L);
        assertThat(row.getChatJid()).isNull();
        assertThat(row.getRawCode()).isEqualTo("429");
        verify(restrictionService, never()).restrictMessageSending(
                eq(17L), eq("RATE_LIMITED"), anyLong(), anyLong());
    }

    @Test
    void recordsAccountReachoutSignalAndProjectsThePlatformDeadline() {
        when(accountMapper.selectActiveByProtocolAccountId("acc-17"))
                .thenReturn(account(17L, "ANDROID"));
        adapter.handleAccountRestricted(new ProtocolAccountRestrictedEvent(
                "evt-reachout", 7L, 17L, "acc-17", "ANDROID", true,
                86_401_000L, "BIZ_QUALITY", "ACCOUNT_REACHOUT_RESTRICTED",
                "463", "reachout restricted", 1_000L, "node-2"));

        ArgumentCaptor<ProtocolRiskEvent> captor = ArgumentCaptor.forClass(ProtocolRiskEvent.class);
        verify(eventMapper).insertIdempotent(captor.capture());
        assertThat(captor.getValue().getScopeType()).isEqualTo("ACCOUNT");
        assertThat(captor.getValue().getIsActive()).isTrue();
        assertThat(captor.getValue().getRestrictedUntil()).isEqualTo(86_401_000L);
        verify(restrictionService).restrictPlatformMessageSending(
                17L, "ACCOUNT_REACHOUT_RESTRICTED", 1_000L, 86_401_000L,
                captor.getValue().getReceivedAt());
    }

    @Test
    void messageReachoutFailureProjectsOnlyTheAccountScopedSignal() {
        when(accountMapper.selectActiveByProtocolAccountId("acc-17"))
                .thenReturn(account(17L, "ANDROID"));
        adapter.handleResult(result(
                "evt-message-reachout", "message.send_result_reported", "MESSAGE_SEND",
                17L, "acc-17", "ANDROID", "hyperlink_task", 8L, 10L,
                "hl:7:8:10", null, "PRIVATE", null, "463",
                "ACCOUNT_REACHOUT_RESTRICTED", "reachout", 3_000L));

        ArgumentCaptor<ProtocolRiskEvent> captor = ArgumentCaptor.forClass(ProtocolRiskEvent.class);
        verify(eventMapper).insertIdempotent(captor.capture());
        assertThat(captor.getValue().getScopeType()).isEqualTo("ACCOUNT");
        verify(restrictionService).restrictMessageSending(
                17L, "ACCOUNT_REACHOUT_RESTRICTED", 3_000L,
                captor.getValue().getReceivedAt());
    }

    @Test
    void recordsChatSuspensionAtChatScopeWithoutMutingTheAccount() {
        when(accountMapper.selectActiveByProtocolAccountId("acc-17"))
                .thenReturn(account(17L, "WEB"));
        adapter.handleResult(result(
                "evt-chat", "group.health_reported", "GROUP_HEALTH",
                17L, "acc-17", "WEB", "group_link", 31L, null,
                null, null, "GROUP", "1203630@g.us", null,
                "CHAT_SUSPENDED", null, 2_000L));

        ArgumentCaptor<ProtocolRiskEvent> captor = ArgumentCaptor.forClass(ProtocolRiskEvent.class);
        verify(eventMapper).insertIdempotent(captor.capture());
        assertThat(captor.getValue().getSignalCode()).isEqualTo("CHAT_SUSPENDED");
        assertThat(captor.getValue().getScopeType()).isEqualTo("CHAT");
        assertThat(captor.getValue().getChatJid()).isEqualTo("1203630@g.us");
        verify(restrictionService, never()).restrictMessageSending(
                eq(17L), eq("CHAT_SUSPENDED"), anyLong(), anyLong());
    }

    @Test
    void recordsAccountRestrictionReleaseWithoutInventingASecondRestriction() {
        when(accountMapper.selectActiveByProtocolAccountId("acc-17"))
                .thenReturn(account(17L, "ANDROID"));
        adapter.handleAccountRestricted(new ProtocolAccountRestrictedEvent(
                "evt-release", 7L, 17L, "acc-17", "ANDROID", false,
                null, "BIZ_QUALITY", "ACCOUNT_REACHOUT_RESTRICTED",
                null, null, 5_000L, "node-2"));

        ArgumentCaptor<ProtocolRiskEvent> captor = ArgumentCaptor.forClass(ProtocolRiskEvent.class);
        verify(eventMapper).insertIdempotent(captor.capture());
        assertThat(captor.getValue().getIsActive()).isFalse();
        verify(restrictionService).clearPlatformMessageSending(
                eq(17L), eq(5_000L), anyLong());
        verify(restrictionService, never()).restrictPlatformMessageSending(
                eq(17L), eq("ACCOUNT_REACHOUT_RESTRICTED"), anyLong(),
                org.mockito.ArgumentMatchers.any(), anyLong());
    }

    @Test
    void rejectsDeclaredAccountThatDoesNotMatchCanonicalProtocolBinding() {
        when(accountMapper.selectActiveByProtocolAccountId("acc-17"))
                .thenReturn(account(17L, "ANDROID"));

        assertThatThrownBy(() -> adapter.handleResult(result(
                "evt-wrong-account", "message.ack", "MESSAGE_ACK",
                18L, "acc-17", "ANDROID", "hyperlink_task", 8L, 10L,
                "hl:7:8:10", "m-1", "PRIVATE", null, "463",
                "ACCOUNT_REACHOUT_RESTRICTED", "reachout", 3_000L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号绑定不一致");

        verifyNoInteractions(eventMapper, restrictionService);
    }

    @Test
    void rejectsProtocolAccountResolvedOutsideTheCurrentTenant() {
        when(accountMapper.selectActiveByProtocolAccountId("acc-other-tenant"))
                .thenAnswer(invocation -> {
                    assertThat(TenantContext.get()).isEqualTo(7L);
                    return null;
                });

        assertThatThrownBy(() -> adapter.handleResult(result(
                "evt-cross-tenant", "group.action_result_reported", "PARTICIPANT_ADD",
                17L, "acc-other-tenant", "ANDROID", "pull_task", 8L, 10L,
                "cmd-10", null, "GROUP", "1203630@g.us", "463",
                "ACCOUNT_REACHOUT_RESTRICTED", "reachout", 3_000L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号绑定不存在");

        verifyNoInteractions(eventMapper, restrictionService);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void rejectsNonexistentProtocolAccount() {
        when(accountMapper.selectActiveByProtocolAccountId("acc-missing")).thenReturn(null);

        assertThatThrownBy(() -> adapter.handleResult(result(
                "evt-missing", "message.ack", "MESSAGE_ACK",
                null, "acc-missing", "ANDROID", "hyperlink_task", 8L, 10L,
                "cmd-10", "m-1", "PRIVATE", null, "429",
                "RATE_LIMITED", "slow down", 3_000L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号绑定不存在");

        verifyNoInteractions(eventMapper, restrictionService);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void rejectsAccountRestrictionWithMismatchedLocalAccountId() {
        when(accountMapper.selectActiveByProtocolAccountId("acc-17"))
                .thenReturn(account(17L, "ANDROID"));

        assertThatThrownBy(() -> adapter.handleAccountRestricted(
                new ProtocolAccountRestrictedEvent(
                        "evt-wrong-restriction", 7L, 18L, "acc-17", "ANDROID", true,
                        86_401_000L, "BIZ_QUALITY", "ACCOUNT_REACHOUT_RESTRICTED",
                        "463", "reachout restricted", 1_000L, "node-2")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号绑定不一致");

        verifyNoInteractions(eventMapper, restrictionService);
    }

    @Test
    void rejectsRiskSignalWithoutTenantInsteadOfSilentlyDroppingIt() {
        ProtocolRiskResultMetadata metadata = new ProtocolRiskResultMetadata(
                new ProtocolRiskResultMetadata.Event(
                        "evt-no-tenant", null, "message.ack", "MESSAGE_ACK", 3_000L, "node-2"),
                new ProtocolRiskResultMetadata.Account(17L, "acc-17", "ANDROID"),
                new ProtocolRiskResultMetadata.Correlation(
                        "hyperlink_task", 8L, 10L, null, "cmd-10", "m-1",
                        "PRIVATE", null, "429"),
                "RATE_LIMITED", "slow down");

        assertThatThrownBy(() -> adapter.handleResult(metadata))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("tenantId");
        verifyNoInteractions(accountMapper, eventMapper, restrictionService);
    }

    private static Account account(Long id, String protocolBackend) {
        Account account = new Account();
        account.setId(id);
        account.setProtocolId(protocolBackend);
        return account;
    }

    private static ProtocolRiskResultMetadata result(
            String eventId, String source, String operationType,
            Long accountId, String protocolAccountId, String protocolBackend,
            String businessType, Long businessId, Long businessItemId,
            String commandId, String messageId, String targetKind, String groupJid,
            String rawCode, String reasonCode, String reasonMessage, Long occurredAt) {
        return new ProtocolRiskResultMetadata(
                new ProtocolRiskResultMetadata.Event(
                        eventId, 7L, source, operationType, occurredAt, "node-2"),
                new ProtocolRiskResultMetadata.Account(
                        accountId, protocolAccountId, protocolBackend),
                new ProtocolRiskResultMetadata.Correlation(
                        businessType, businessId, businessItemId, null, commandId, messageId,
                        targetKind, groupJid, rawCode),
                reasonCode, reasonMessage);
    }
}
