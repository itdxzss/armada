package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.WhatsappGroupDepartedMemberMapper;
import com.armada.group.model.dto.WhatsappGroupDepartureFact;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WhatsappGroupDepartedMemberServiceImplTest {

    @Mock
    private WhatsappGroupDepartedMemberMapper mapper;

    @Test
    void saveLatestSortsLocksAndWritesEachFactIndependently() {
        WhatsappGroupDepartureFact second = fact("group-b@g.us", "2@s.whatsapp.net");
        WhatsappGroupDepartureFact first = fact("group-a@g.us", "1@s.whatsapp.net");
        WhatsappGroupDepartedMemberServiceImpl service =
                new WhatsappGroupDepartedMemberServiceImpl(mapper);

        service.saveLatest(List.of(second, first));

        InOrder order = inOrder(mapper);
        order.verify(mapper).upsertIdentity(eq(first), anyLong());
        order.verify(mapper).updateIfNewer(eq(first), anyLong());
        order.verify(mapper).upsertIdentity(eq(second), anyLong());
        order.verify(mapper).updateIfNewer(eq(second), anyLong());
    }

    @Test
    void saveLatestIgnoresEmptyBatch() {
        WhatsappGroupDepartedMemberServiceImpl service =
                new WhatsappGroupDepartedMemberServiceImpl(mapper);

        service.saveLatest(List.of());

        verify(mapper, never()).upsertIdentity(org.mockito.ArgumentMatchers.any(), anyLong());
        verify(mapper, never()).updateIfNewer(org.mockito.ArgumentMatchers.any(), anyLong());
    }

    @Test
    void findByGroupJidsChunksLargeQueries() {
        List<String> groupJids = java.util.stream.IntStream.range(0, 501)
                .mapToObj(index -> "group-%03d@g.us".formatted(index))
                .toList();
        when(mapper.selectByGroupJids(eq(7L), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of());
        WhatsappGroupDepartedMemberServiceImpl service =
                new WhatsappGroupDepartedMemberServiceImpl(mapper);

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
