package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.WhatsappGroupDepartedMemberMapper;
import com.armada.group.model.dto.WhatsappGroupDepartureFact;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WhatsappGroupDepartedMemberServiceImplTest {

    @Mock
    private WhatsappGroupDepartedMemberMapper mapper;

    @Mock
    private AccountGroupCurrentSnapshotPersistenceImpl currentPersistence;

    @Test
    void saveLatestWritesOnlyCurrentParticipantFacts() {
        WhatsappGroupDepartureFact second = fact("group-b@g.us", "2@s.whatsapp.net");
        WhatsappGroupDepartureFact first = fact("group-a@g.us", "1@s.whatsapp.net");
        List<WhatsappGroupDepartureFact> facts = List.of(second, first);
        WhatsappGroupDepartedMemberServiceImpl service =
                new WhatsappGroupDepartedMemberServiceImpl(mapper, currentPersistence);

        service.saveLatest(facts);

        verify(currentPersistence).applyParticipantDepartures(facts);
        verifyNoInteractions(mapper);
    }

    @Test
    void saveLatestIgnoresEmptyBatch() {
        WhatsappGroupDepartedMemberServiceImpl service =
                new WhatsappGroupDepartedMemberServiceImpl(mapper, currentPersistence);

        service.saveLatest(List.of());

        verifyNoInteractions(mapper, currentPersistence);
    }

    @Test
    void findByGroupJidsChunksLargeQueries() {
        List<String> groupJids = java.util.stream.IntStream.range(0, 501)
                .mapToObj(index -> "group-%03d@g.us".formatted(index))
                .toList();
        when(mapper.selectByGroupJids(eq(7L), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of());
        WhatsappGroupDepartedMemberServiceImpl service =
                new WhatsappGroupDepartedMemberServiceImpl(mapper, currentPersistence);

        assertThat(service.findByGroupJids(7L, groupJids)).isEmpty();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper, times(2)).selectByGroupJids(eq(7L), captor.capture());
        assertThat(captor.getAllValues()).extracting(List::size).containsExactly(500, 1);
    }

    private static WhatsappGroupDepartureFact fact(String groupJid, String participantJid) {
        return new WhatsappGroupDepartureFact(
                7L, groupJid, participantJid, "15550000001",
                100L, "LEFT", 100L, "event-1", "HISTORY_SYNC");
    }
}
