package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.group.mapper.WhatsappGroupMemberCacheMapper;
import com.armada.group.model.dto.GroupParticipantObservation;
import com.armada.group.model.enums.WhatsappGroupMemberStateSource;
import com.armada.group.model.vo.WhatsappGroupMemberStateVO;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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

    @AfterEach
    void clearTenant() {
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
        when(accountMapper.selectActiveByWsPhones(List.of("15550000001")))
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

        verify(accountMapper, never()).selectActiveByWsPhones(
                org.mockito.ArgumentMatchers.anyList());
        verify(currentSnapshotPersistence, never()).applyControlledParticipantObservation(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
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
        account.setWsPhone(phone);
        return account;
    }
}
