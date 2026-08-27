package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.group.mapper.WhatsappGroupMemberCacheMapper;
import com.armada.group.model.dto.GroupParticipantObservation;
import com.armada.group.model.dto.ControlledAccountGroupTransition;
import com.armada.group.model.enums.WhatsappGroupMemberStateSource;
import com.armada.group.model.vo.WhatsappGroupMemberStateVO;
import com.armada.shared.tenant.TenantContext;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 群成员增量观察写入当前模型的单测。 */
@ExtendWith(MockitoExtension.class)
class GroupParticipantObservationServiceImplTest {

    @Mock private WhatsappGroupMemberCacheMapper memberStateMapper;
    @Mock private AccountMapper accountMapper;
    @Mock private AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence;

    @BeforeEach
    void setUpScope() {
        DataScopeContext.open(DataScope.self(7L));
        Account observer = account(10L, "15550000010");
        lenient().when(accountMapper.selectActiveById(10L)).thenReturn(observer);
    }

    @AfterEach
    void clearTenant() {
        DataScopeContext.clear();
        TenantContext.clear();
    }

    @Test
    void winningRoleFactUpdatesCurrentParticipantAndControlledBinding() {
        GroupParticipantObservation input = observation(
                "123456789012345@lid", "+1 555 000 0001", true, true,
                WhatsappGroupMemberStateSource.ROLE_EVENT, 1_000L, "event-1");
        when(memberStateMapper.selectStatesByParticipantJids(
                7L, "120363-test@g.us", List.of("123456789012345@lid")))
                .thenReturn(List.of(new WhatsappGroupMemberStateVO(
                        "123456789012345@lid", "15550000001", true, false,
                        "admin", true, "ROLE_EVENT", 1_000L, "event-1")));
        when(accountMapper.selectActiveByWsPhonesForScope(
                List.of("15550000001"), DataScope.self(7L)))
                .thenReturn(List.of(account(77L, "15550000001")));

        service().apply(List.of(input));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroupParticipantObservation>> observations =
                ArgumentCaptor.forClass(List.class);
        verify(currentSnapshotPersistence).applyParticipantObservations(observations.capture());
        assertThat(observations.getValue()).singleElement().satisfies(value -> {
            assertThat(value.groupJid()).isEqualTo("120363-test@g.us");
            assertThat(value.participantJid()).isEqualTo("123456789012345@lid");
            assertThat(value.source()).isEqualTo(WhatsappGroupMemberStateSource.ROLE_EVENT);
        });
        verify(currentSnapshotPersistence).applyControlledParticipantObservation(
                77L, "120363-test@g.us", true, true,
                1_000L, "event-1", "WGP2_PROMOTE");
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void unresolvedLidDoesNotCreateControlledBinding() {
        GroupParticipantObservation input = observation(
                "123456789012345@lid", null, true, true,
                WhatsappGroupMemberStateSource.ROLE_EVENT, 1_000L, "event-lid");
        when(memberStateMapper.selectStatesByParticipantJids(
                7L, "120363-test@g.us", List.of("123456789012345@lid")))
                .thenReturn(List.of(new WhatsappGroupMemberStateVO(
                        "123456789012345@lid", null, true, false,
                        "admin", true, "ROLE_EVENT", 1_000L)));

        service().apply(List.of(input));

        verify(accountMapper, never()).selectActiveByWsPhonesForScope(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(DataScope.class));
        verify(currentSnapshotPersistence, never()).applyControlledParticipantObservation(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void reconcileControlledMembershipsDedupesCandidatesAndUpdatesBinding() {
        when(memberStateMapper.selectStatesByParticipantJids(
                7L, "120363-test@g.us",
                List.of("123456789012345@lid", "15550000001@s.whatsapp.net")))
                .thenReturn(List.of(new WhatsappGroupMemberStateVO(
                        "15550000001@s.whatsapp.net", "15550000001", false, false,
                        "member", false, "REMOVE_EVENT", 2_000L, "event-remove")));
        when(accountMapper.selectActiveByWsPhonesForScope(
                List.of("15550000001"), DataScope.self(7L)))
                .thenReturn(List.of(account(77L, "15550000001")));

        when(currentSnapshotPersistence.applyControlledParticipantObservation(
                77L, "120363-test@g.us", false, false,
                2_000L, "event-remove", "WGP2_REMOVE"))
                .thenReturn(false);

        List<ControlledAccountGroupTransition> transitions = service().reconcileControlledMemberships(
                7L, "120363-TEST@G.US", List.of(
                "15550000001@s.whatsapp.net", "123456789012345@LID",
                "123456789012345@lid", " "));

        verify(currentSnapshotPersistence).applyControlledParticipantObservation(
                77L, "120363-test@g.us", false, false,
                2_000L, "event-remove", "WGP2_REMOVE");
        assertThat(transitions).isEmpty();
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void reconcileControlledMembershipsReturnsOnlyNewInGroupTransitions() {
        when(memberStateMapper.selectStatesByParticipantJids(
                7L, "120363-test@g.us", List.of("15550000001@s.whatsapp.net")))
                .thenReturn(List.of(new WhatsappGroupMemberStateVO(
                        "15550000001@s.whatsapp.net", "15550000001", false, false,
                        "member", true, "ADD_EVENT", 2_000L, "event-add")));
        when(accountMapper.selectActiveByWsPhonesForScope(
                List.of("15550000001"), DataScope.self(7L)))
                .thenReturn(List.of(account(77L, "15550000001")));
        when(currentSnapshotPersistence.applyControlledParticipantObservation(
                77L, "120363-test@g.us", true, false,
                2_000L, "event-add", "WGP2_ADD"))
                .thenReturn(true);

        List<ControlledAccountGroupTransition> transitions = service().reconcileControlledMemberships(
                7L, "120363-test@g.us", List.of("15550000001@s.whatsapp.net"));

        assertThat(transitions).containsExactly(new ControlledAccountGroupTransition(
                77L, "120363-test@g.us"));
    }

    @Test
    void reconcileControlledJoinsDetectsTransitionBeforeGenericAddIsWritten() {
        when(accountMapper.selectActiveByWsPhonesForScope(
                List.of("15550000001"), DataScope.self(7L)))
                .thenReturn(List.of(account(77L, "15550000001")));
        when(currentSnapshotPersistence.applyControlledParticipantObservation(
                77L, "120363-test@g.us", true, false,
                2_000L, "event-add", "WGP2_ADD"))
                .thenReturn(true);

        List<ControlledAccountGroupTransition> transitions = service().reconcileControlledJoins(
                7L, "120363-TEST@G.US",
                List.of("123456789012345@lid", "15550000001@s.whatsapp.net"),
                2_000L, "event-add");

        assertThat(transitions).containsExactly(new ControlledAccountGroupTransition(
                77L, "120363-test@g.us"));
        verify(memberStateMapper, never()).selectStatesByParticipantJids(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList());
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void reconcileControlledMembershipsWithoutMatchingRowsDoesNothing() {
        when(memberStateMapper.selectStatesByParticipantJids(
                7L, "120363-test@g.us", List.of("123456789012345@lid")))
                .thenReturn(List.of());

        service().reconcileControlledMemberships(
                7L, "120363-test@g.us", List.of("123456789012345@lid"));

        verify(accountMapper, never()).selectActiveByWsPhonesForScope(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(DataScope.class));
    }

    @Test
    void rejectsObservationFromHistoricalUnownedAccountBeforeWritingFacts() {
        Account unowned = account(10L, "15550000010");
        unowned.setOwnerUserId(null);
        when(accountMapper.selectActiveById(10L)).thenReturn(unowned);

        assertThatThrownBy(() -> service().apply(List.of(observation(
                "15550000001@s.whatsapp.net", "15550000001", true, false,
                WhatsappGroupMemberStateSource.ADD_EVENT, 1_000L, "event-unowned"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少数据归属");

        verify(currentSnapshotPersistence, never()).applyParticipantObservations(
                org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void standaloneReconciliationFailsWhenDataScopeIsMissing() {
        DataScopeContext.clear();

        assertThatThrownBy(() -> service().reconcileControlledJoins(
                7L, "120363-test@g.us", List.of("15550000001@s.whatsapp.net"),
                2_000L, "event-add"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("数据访问范围");

        verify(accountMapper, never()).selectActiveByWsPhonesForScope(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(DataScope.class));
    }

    private GroupParticipantObservationServiceImpl service() {
        return new GroupParticipantObservationServiceImpl(
                memberStateMapper, accountMapper, currentSnapshotPersistence);
    }

    private static GroupParticipantObservation observation(
            String participantJid,
            String phone,
            boolean inGroup,
            boolean admin,
            WhatsappGroupMemberStateSource source,
            long observedAt,
            String eventId) {
        return new GroupParticipantObservation(
                7L, 10L, "120363-TEST@G.US", "15550000001@s.whatsapp.net",
                participantJid, phone, inGroup, admin, source, observedAt, eventId);
    }

    private static Account account(Long id, String phone) {
        Account account = new Account();
        account.setId(id);
        account.setTenantId(7L);
        account.setOwnerUserId(7L);
        account.setWsPhone(phone);
        return account;
    }
}
