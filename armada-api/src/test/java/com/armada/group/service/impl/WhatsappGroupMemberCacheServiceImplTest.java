package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.WhatsappGroupMemberCacheMapper;
import com.armada.group.model.dto.WhatsappGroupDepartureFact;
import com.armada.group.model.dto.WhatsappGroupJoinFact;
import com.armada.group.model.dto.WhatsappGroupMemberCacheHeaderWrite;
import com.armada.group.model.dto.WhatsappGroupMemberStateWrite;
import com.armada.group.model.vo.WhatsappGroupMemberCacheRow;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WhatsappGroupMemberCacheServiceImplTest {

    @Mock private WhatsappGroupMemberCacheMapper mapper;

    @Test
    void replaceCompleteSnapshotArbitratesHeaderThenAtomicallyReplacesMembers() {
        AtomicReference<WhatsappGroupMemberCacheHeaderWrite> writtenHeader = new AtomicReference<>();
        when(mapper.upsertHeader(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> {
                    writtenHeader.set(invocation.getArgument(0));
                    return 1;
                });
        when(mapper.selectSnapshotVersionForUpdate(7L, "120363-test@g.us"))
                .thenAnswer(ignored -> writtenHeader.get().snapshotVersion());
        when(mapper.selectByGroupJids(7L, List.of("120363-test@g.us"))).thenReturn(List.of(
                new WhatsappGroupMemberCacheRow(
                        "120363-test@g.us", "真实群", true, 1_000L, 10L,
                        "15550000001@s.whatsapp.net", "15550000001",
                        true, false, "admin", true, "FULL_SNAPSHOT", 1_000L)));
        WhatsappGroupMemberCacheServiceImpl service = new WhatsappGroupMemberCacheServiceImpl(mapper);
        GroupMetadataResult metadata = new GroupMetadataResult(
                "120363-test@g.us", "真实群", true, null, null, null, null,
                null, false, null, false, true,
                List.of(new GroupParticipantResult(
                        "15550000001:3@s.whatsapp.net", "+1 555 000 0001",
                        true, false, "admin")));

        var result = service.replaceCompleteSnapshot(
                7L, 10L, "120363-TEST@G.US", metadata, 1_000L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WhatsappGroupMemberStateWrite>> states = ArgumentCaptor.forClass(List.class);
        verify(mapper).upsertStates(states.capture(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(states.getValue()).singleElement().satisfies(state -> {
            assertThat(state.groupJid()).isEqualTo("120363-test@g.us");
            assertThat(state.participantJid()).isEqualTo("15550000001@s.whatsapp.net");
            assertThat(state.phone()).isEqualTo("15550000001");
            assertThat(state.stateSource()).isEqualTo("FULL_SNAPSHOT");
            assertThat(state.inGroup()).isTrue();
        });
        ArgumentCaptor<WhatsappGroupMemberCacheHeaderWrite> header =
                ArgumentCaptor.forClass(WhatsappGroupMemberCacheHeaderWrite.class);
        verify(mapper).upsertHeader(header.capture(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(header.getValue().subject()).isEqualTo("真实群");
        assertThat(result.members()).hasSize(1);

        InOrder order = inOrder(mapper);
        order.verify(mapper).upsertHeader(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
        order.verify(mapper).selectSnapshotVersionForUpdate(7L, "120363-test@g.us");
        order.verify(mapper).upsertStates(anyList(), org.mockito.ArgumentMatchers.anyLong());
        order.verify(mapper).markSnapshotMissing(
                eq(7L), eq("120363-test@g.us"), org.mockito.ArgumentMatchers.anyString(),
                eq(1_000L), org.mockito.ArgumentMatchers.anyString(), eq(10L),
                org.mockito.ArgumentMatchers.anyLong());
        order.verify(mapper).selectByGroupJids(7L, List.of("120363-test@g.us"));
    }

    @Test
    void olderSnapshotDoesNotWriteMemberRowsAfterHeaderArbitrationLoses() {
        when(mapper.selectSnapshotVersionForUpdate(7L, "120363-test@g.us"))
                .thenReturn("newer-snapshot-version");
        when(mapper.selectByGroupJids(7L, List.of("120363-test@g.us"))).thenReturn(List.of(
                new WhatsappGroupMemberCacheRow(
                        "120363-test@g.us", "较新群", false, 2_000L, 11L,
                        "15550000002@s.whatsapp.net", "15550000002",
                        false, false, "", true, "FULL_SNAPSHOT", 2_000L)));
        WhatsappGroupMemberCacheServiceImpl service = new WhatsappGroupMemberCacheServiceImpl(mapper);
        GroupMetadataResult older = new GroupMetadataResult(
                "120363-test@g.us", "旧群", false, null, null, null, null,
                null, false, null, false, true,
                List.of(new GroupParticipantResult(
                        "15550000001@s.whatsapp.net", "15550000001",
                        false, false, "")));

        var result = service.replaceCompleteSnapshot(
                7L, 10L, "120363-test@g.us", older, 1_000L);

        assertThat(result.subject()).isEqualTo("较新群");
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never())
                .upsertStates(org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never())
                .markSnapshotMissing(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void eventsUpdateCachedMembershipWithExplicitSources() {
        WhatsappGroupMemberCacheServiceImpl service = new WhatsappGroupMemberCacheServiceImpl(mapper);
        service.applyJoins(List.of(new WhatsappGroupJoinFact(
                7L, "120363-test@g.us", "15550000001@s.whatsapp.net", "15550000001",
                900L, 900L, "add-1", 10L)));
        service.applyDepartures(List.of(new WhatsappGroupDepartureFact(
                7L, "120363-test@g.us", "15550000002@s.whatsapp.net", "15550000002",
                950L, "REMOVED", 950L, "remove-1", "WGP2_NOTIFICATION")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WhatsappGroupMemberStateWrite>> states = ArgumentCaptor.forClass(List.class);
        verify(mapper, org.mockito.Mockito.times(2))
                .upsertStates(states.capture(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(states.getAllValues().get(0)).singleElement().satisfies(state -> {
            assertThat(state.inGroup()).isTrue();
            assertThat(state.stateSource()).isEqualTo("ADD_EVENT");
            assertThat(state.admin()).isFalse();
            assertThat(state.owner()).isFalse();
            assertThat(state.role()).isEqualTo("member");
        });
        assertThat(states.getAllValues().get(1)).singleElement().satisfies(state -> {
            assertThat(state.inGroup()).isFalse();
            assertThat(state.stateSource()).isEqualTo("REMOVE_EVENT");
        });
    }
}
