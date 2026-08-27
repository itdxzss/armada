package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.group.model.dto.WhatsappGroupJoinFact;
import com.armada.group.service.WhatsappGroupMemberJoinFactService;
import com.armada.group.service.WhatsappGroupMemberCacheService;
import com.armada.platform.kafka.consumer.account.ProtocolGroupJoinEvent;
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
class ProtocolGroupJoinSinkImplTest {

    @Mock private AccountMapper accountMapper;
    @Mock private WhatsappGroupMemberJoinFactService joinFactService;
    @Mock private WhatsappGroupMemberCacheService memberCacheService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        DataScopeContext.clear();
    }

    @Test
    void handleJoinsValidatesCurrentBindingAndStoresCanonicalFact() {
        when(accountMapper.selectActiveById(10L)).thenReturn(account("android-10", 501L));
        TenantContext.set(99L);
        ProtocolGroupJoinSinkImpl sink = new ProtocolGroupJoinSinkImpl(
                accountMapper, joinFactService, memberCacheService);

        sink.handleJoins(event("android-10"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WhatsappGroupJoinFact>> captor = ArgumentCaptor.forClass(List.class);
        verify(joinFactService).saveLatest(captor.capture());
        verify(memberCacheService).applyJoins(captor.getValue());
        assertThat(captor.getValue()).singleElement().satisfies(fact -> {
            assertThat(fact.tenantId()).isEqualTo(7L);
            assertThat(fact.groupJid()).isEqualTo("120363-test@g.us");
            assertThat(fact.participantJid()).isEqualTo("15550000002@s.whatsapp.net");
            assertThat(fact.phone()).isEqualTo("15550000002");
            assertThat(fact.joinedAt()).isEqualTo(900L);
            assertThat(fact.observerAccountId()).isEqualTo(10L);
        });
        assertThat(TenantContext.get()).isEqualTo(99L);
    }

    @Test
    void handleJoinsRejectsStaleProtocolBinding() {
        when(accountMapper.selectActiveById(10L)).thenReturn(account("android-current", 501L));
        ProtocolGroupJoinSinkImpl sink = new ProtocolGroupJoinSinkImpl(
                accountMapper, joinFactService, memberCacheService);

        sink.handleJoins(event("android-stale"));

        verify(joinFactService, never()).saveLatest(org.mockito.ArgumentMatchers.anyList());
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void handleJoinsKeepsStableLidAndAddsTrustedPhoneAlias() {
        when(accountMapper.selectActiveById(10L)).thenReturn(account("android-10", 501L));
        ProtocolGroupJoinSinkImpl sink = new ProtocolGroupJoinSinkImpl(
                accountMapper, joinFactService, memberCacheService);
        ProtocolGroupJoinEvent join = new ProtocolGroupJoinEvent(
                "event-lid", 7L, 10L, "android-10", "120363-test@g.us",
                "WGP2_NOTIFICATION", 1_000L,
                List.of(new ProtocolGroupJoinEvent.Participant(
                        "123456789012345:7@lid", "+52 181 292 30974", 900L, "source-lid")));

        sink.handleJoins(join);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WhatsappGroupJoinFact>> captor = ArgumentCaptor.forClass(List.class);
        verify(joinFactService).saveLatest(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(fact -> {
            assertThat(fact.participantJid()).isEqualTo("123456789012345@lid");
            assertThat(fact.phone()).isEqualTo("5218129230974");
        });
    }

    @Test
    void handleJoinsRejectsHistoricalUnownedAccount() {
        when(accountMapper.selectActiveById(10L)).thenReturn(account("android-10", null));
        ProtocolGroupJoinSinkImpl sink = new ProtocolGroupJoinSinkImpl(
                accountMapper, joinFactService, memberCacheService);

        assertThatThrownBy(() -> sink.handleJoins(event("android-10")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.ACCESS_DENIED.code());

        verify(joinFactService, never()).saveLatest(org.mockito.ArgumentMatchers.anyList());
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

    private static ProtocolGroupJoinEvent event(String protocolAccountId) {
        return new ProtocolGroupJoinEvent(
                "event-1", 7L, 10L, protocolAccountId, " 120363-TEST@G.US ",
                "WGP2_NOTIFICATION", 1_000L,
                List.of(new ProtocolGroupJoinEvent.Participant(
                        "15550000002:17@s.whatsapp.net", "+1 555 000 0002", 900L, "source-add-1")));
    }
}
