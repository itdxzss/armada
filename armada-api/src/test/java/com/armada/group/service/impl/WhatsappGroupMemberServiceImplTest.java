package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.WhatsappGroupMemberMapper;
import com.armada.group.model.dto.WhatsappGroupParticipant;
import com.armada.group.model.dto.WhatsappGroupParticipantsChangedEvent;
import com.armada.group.model.entity.WhatsappGroupMember;
import com.armada.group.model.vo.AccountGroupBaselineRow;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WhatsappGroupMemberServiceImplTest {

    private static final String GROUP_JID = "120363000000000001@g.us";

    @Mock
    private WhatsappGroupMemberMapper memberMapper;

    @Mock
    private AccountGroupMembershipMapper accountMembershipMapper;

    @Mock
    private GroupLinkRegistryService groupLinkRegistryService;

    private WhatsappGroupMemberServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContext.set(7L);
        service = new WhatsappGroupMemberServiceImpl(
                memberMapper, accountMembershipMapper, groupLinkRegistryService);
        when(memberMapper.insertMemberFact(any(WhatsappGroupMember.class))).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void partialOrCountMismatchedSnapshotNeverMarksUnseenMembersMissing() {
        List<WhatsappGroupParticipant> partial = List.of(participant("2@s.whatsapp.net"));

        service.replaceCurrentMembers(
                10L, 20L, GROUP_JID, partial, 100, true, false, true,
                5_000L, "snapshot-partial");

        verify(memberMapper).insertMemberFact(any(WhatsappGroupMember.class));
        verify(memberMapper).upsertMember(any(WhatsappGroupMember.class));
        verify(memberMapper).lockGroupLink(20L);
        verify(memberMapper, never()).selectMissingCurrentMembers(
                any(), anyList(), org.mockito.ArgumentMatchers.anyLong(), any());
        verify(memberMapper, never()).markMissingMembers(
                anyList(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                any(),
                any());
        verify(memberMapper, never()).insertCompleteSnapshot(
                any(),
                any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong(),
                any(),
                any(),
                any(),
                any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void completeSnapshotSortsLocksRecordsMissingFactsAndAdvancesWatermark() {
        WhatsappGroupMember missing = new WhatsappGroupMember();
        missing.setId(99L);
        missing.setGroupLinkId(20L);
        missing.setGroupJid(GROUP_JID);
        missing.setMemberJid("0@s.whatsapp.net");
        when(memberMapper.selectMissingCurrentMembers(
                eq(GROUP_JID),
                eq(List.of("1@s.whatsapp.net", "2@s.whatsapp.net")),
                eq(5_000L),
                eq("snapshot-complete")))
                .thenReturn(List.of(missing));

        service.replaceCurrentMembers(
                10L,
                20L,
                GROUP_JID,
                List.of(participant("2@s.whatsapp.net"), participant("1@s.whatsapp.net")),
                2,
                true,
                false,
                true,
                5_000L,
                "snapshot-complete");

        ArgumentCaptor<WhatsappGroupMember> facts = ArgumentCaptor.forClass(WhatsappGroupMember.class);
        verify(memberMapper, times(3)).insertMemberFact(facts.capture());
        assertThat(facts.getAllValues().stream()
                .filter(fact -> fact.getMembershipStatus() == 1)
                .toList())
                .extracting(WhatsappGroupMember::getMemberJid)
                .containsExactly("1@s.whatsapp.net", "2@s.whatsapp.net");
        assertThat(facts.getAllValues().stream()
                .filter(fact -> fact.getMembershipStatus() == 1)
                .toList())
                .extracting(WhatsappGroupMember::getStatusSourceEventId)
                .containsOnly("snapshot-complete");
        assertThat(facts.getAllValues())
                .anySatisfy(fact -> {
                    assertThat(fact.getMemberJid()).isEqualTo("0@s.whatsapp.net");
                    assertThat(fact.getStatusSourceEventId()).isEqualTo("snapshot-complete");
                    assertThat(fact.getMembershipStatus()).isEqualTo(5);
                });
        verify(memberMapper).markMissingMembers(
                eq(List.of(99L)),
                eq(5_000L),
                org.mockito.ArgumentMatchers.anyLong(),
                eq(10L),
                eq("snapshot-complete"));
        verify(memberMapper).insertCompleteSnapshot(
                eq(20L),
                eq(GROUP_JID),
                eq(2),
                eq(5_000L),
                eq("snapshot-complete"),
                eq(10L),
                eq(false),
                eq(true),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void preciseEventUsesProtocolSourceEventIdForFactIdempotency() {
        AccountGroupBaselineRow account = new AccountGroupBaselineRow();
        account.setProtocolId("android");
        account.setProtocolAccountId("protocol-account-1");
        when(accountMembershipMapper.selectAccountBaselineRow(10L)).thenReturn(account);
        when(groupLinkRegistryService.registerAccountObservedGroup(
                eq(GROUP_JID), eq(null), any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(20L);

        service.applyParticipantsChanged(new WhatsappGroupParticipantsChangedEvent(
                "envelope-event-1",
                7L,
                10L,
                "protocol-account-1",
                GROUP_JID,
                "remove",
                5_000L,
                "android_wgp2",
                "protocol-source-event-1",
                List.of(participant("2@s.whatsapp.net"))));

        verify(memberMapper).insertMemberFact(org.mockito.ArgumentMatchers.argThat(
                fact -> "protocol-source-event-1".equals(fact.getStatusSourceEventId())
                        && fact.getMembershipStatus() == 3));
    }

    private static WhatsappGroupParticipant participant(String memberJid) {
        return new WhatsappGroupParticipant(memberJid, memberJid, null, "member", false, false);
    }
}
