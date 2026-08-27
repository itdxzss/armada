package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.group.model.dto.WhatsappGroupDepartureFact;
import com.armada.group.service.WhatsappGroupDepartedMemberService;
import com.armada.group.service.WhatsappGroupMemberCacheService;
import com.armada.platform.kafka.consumer.account.ProtocolGroupDepartureEvent;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProtocolGroupDepartureSinkImplTest {

    @Mock private AccountMapper accountMapper;
    @Mock private WhatsappGroupDepartedMemberService departedMemberService;
    @Mock private WhatsappGroupMemberCacheService memberCacheService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        DataScopeContext.clear();
    }

    @Test
    void handleDeparturesValidatesCurrentTenantAccountBindingAndRestoresContext() {
        when(accountMapper.selectActiveById(10L)).thenAnswer(ignored -> {
            assertThat(TenantContext.get()).isEqualTo(7L);
            return account("android-10", 501L);
        });
        TenantContext.set(99L);
        ProtocolGroupDepartureSinkImpl sink = new ProtocolGroupDepartureSinkImpl(
                accountMapper, departedMemberService, memberCacheService);

        sink.handleDepartures(event("android-10"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WhatsappGroupDepartureFact>> captor = ArgumentCaptor.forClass(List.class);
        verify(departedMemberService).saveLatest(captor.capture());
        verify(memberCacheService).applyDepartures(captor.getValue());
        assertThat(captor.getValue()).singleElement().satisfies(fact -> {
            assertThat(fact.tenantId()).isEqualTo(7L);
            assertThat(fact.groupJid()).isEqualTo("120363-test@g.us");
            assertThat(fact.participantJid()).isEqualTo("15550000002@s.whatsapp.net");
            assertThat(fact.phone()).isEqualTo("15550000002");
            assertThat(fact.exitType()).isEqualTo("LEFT");
        });
        assertThat(TenantContext.get()).isEqualTo(99L);
    }

    @Test
    void handleDeparturesRejectsStaleProtocolBinding() {
        when(accountMapper.selectActiveById(10L)).thenReturn(account("android-current", 501L));
        ProtocolGroupDepartureSinkImpl sink = new ProtocolGroupDepartureSinkImpl(
                accountMapper, departedMemberService, memberCacheService);

        sink.handleDepartures(event("android-stale"));

        verify(departedMemberService, never()).saveLatest(org.mockito.ArgumentMatchers.anyList());
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void handleDeparturesDerivesPhoneFromPnButNotFromLid() {
        when(accountMapper.selectActiveById(10L)).thenReturn(account("android-10", 501L));
        ProtocolGroupDepartureSinkImpl sink = new ProtocolGroupDepartureSinkImpl(
                accountMapper, departedMemberService, memberCacheService);
        ProtocolGroupDepartureEvent departure = new ProtocolGroupDepartureEvent(
                "event-2", 7L, 10L, "android-10", "120363-test@g.us",
                "HISTORY_SYNC", 1_000L,
                List.of(
                        new ProtocolGroupDepartureEvent.Participant(
                                "15550000003:9@s.whatsapp.net", null,
                                "LEFT", 900L, "source-2"),
                        new ProtocolGroupDepartureEvent.Participant(
                                "123456789012345@lid", null,
                                "REMOVED", 901L, "source-3"),
                        new ProtocolGroupDepartureEvent.Participant(
                                "223456789012345:8@lid", "+52 181 292 30974",
                                "UNKNOWN", 902L, "source-4")));

        sink.handleDepartures(departure);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WhatsappGroupDepartureFact>> captor = ArgumentCaptor.forClass(List.class);
        verify(departedMemberService).saveLatest(captor.capture());
        assertThat(captor.getValue()).satisfiesExactly(
                fact -> {
                    assertThat(fact.participantJid()).isEqualTo("15550000003@s.whatsapp.net");
                    assertThat(fact.phone()).isEqualTo("15550000003");
                },
                fact -> {
                    assertThat(fact.participantJid()).isEqualTo("123456789012345@lid");
                    assertThat(fact.phone()).isNull();
                },
                fact -> {
                    assertThat(fact.participantJid()).isEqualTo("223456789012345@lid");
                    assertThat(fact.phone()).isEqualTo("5218129230974");
                });
    }

    @Test
    void handleDeparturesRejectsHistoricalUnownedAccount() {
        when(accountMapper.selectActiveById(10L)).thenReturn(account("android-10", null));
        ProtocolGroupDepartureSinkImpl sink = new ProtocolGroupDepartureSinkImpl(
                accountMapper, departedMemberService, memberCacheService);

        assertThatThrownBy(() -> sink.handleDepartures(event("android-10")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.ACCESS_DENIED.code());

        verify(departedMemberService, never()).saveLatest(org.mockito.ArgumentMatchers.anyList());
        assertThat(DataScopeContext.current()).isEmpty();
        assertThat(TenantContext.get()).isNull();
    }

    private static Account account(String protocolAccountId, Long ownerUserId) {
        Account account = new Account();
        account.setId(10L);
        account.setOwnerUserId(ownerUserId);
        account.setProtocolAccountId(protocolAccountId);
        return account;
    }

    private static ProtocolGroupDepartureEvent event(String protocolAccountId) {
        return new ProtocolGroupDepartureEvent(
                "event-1", 7L, 10L, protocolAccountId, " 120363-TEST@G.US ",
                "WGP2_NOTIFICATION", 1_000L,
                List.of(new ProtocolGroupDepartureEvent.Participant(
                        "15550000002:17@s.whatsapp.net", "+1 555 000 0002", "LEFT", 900L, "source-1")));
    }
}
