package com.armada.account.contact;

import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.contact.mapper.AccountContactSyncMapper;
import com.armada.account.contact.model.entity.AccountContact;
import com.armada.account.contact.model.entity.AccountContactSync;
import com.armada.account.contact.service.AccountContactNormalizer;
import com.armada.account.contact.service.impl.AccountContactSnapshotSink;
import com.armada.account.service.AccountProfileService;
import com.armada.platform.kafka.consumer.contact.AccountContactsReportedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 通讯录快照落库的纯 Mockito 测试。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountContactSnapshotSinkTest {

    private static final long CUTOFF = 1_700_000_005_000L;

    @Mock
    private AccountContactMapper contactMapper;
    @Mock
    private AccountContactSyncMapper syncMapper;
    @Mock
    private AccountProfileService accountProfileService;

    private AccountContactSnapshotSink sink() {
        return new AccountContactSnapshotSink(
                contactMapper, syncMapper, accountProfileService,
                new AccountContactNormalizer(), () -> 2_000L);
    }

    private static AccountContactsReportedEvent chunk(
            int chunkSeq, int totalCount, boolean complete, int contactCount) {
        return new AccountContactsReportedEvent(
                "evt_1", 5L, 11L, "acc_1", "snap-1",
                1_700_000_000_000L, CUTOFF, complete,
                chunkSeq, 2, totalCount,
                IntStream.range(0, contactCount)
                        .mapToObj(i -> new AccountContactsReportedEvent.ReportedContact(
                                "8613800000" + String.format("%03d", i),
                                "8613800000" + String.format("%03d", i) + "@s.whatsapp.net",
                                "名字" + i, null, null, null))
                        .toList());
    }

    private AccountContactSync capturedSyncState() {
        ArgumentCaptor<AccountContactSync> captor =
                ArgumentCaptor.forClass(AccountContactSync.class);
        verify(syncMapper).upsert(captor.capture());
        return captor.getValue();
    }

    @Test
    void stampsProtocolCutoffAsSyncedAtNotLocalClock() {
        // 这是整件事的目的：synced_at 必须是协议给的真实快照时间，不是 armada 的 now
        when(contactMapper.countBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(1);

        sink().handle(chunk(0, 1, true, 1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountContact>> captor = ArgumentCaptor.forClass(List.class);
        verify(contactMapper).upsertBatch(captor.capture());
        assertThat(captor.getValue().get(0).getSyncedAt()).isEqualTo(CUTOFF);
        assertThat(captor.getValue().get(0).getTenantId()).isEqualTo(5L);
    }

    @Test
    void deletesStaleOnlyWhenChunksAreAllIn() {
        when(contactMapper.countBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(2);

        sink().handle(chunk(1, 2, true, 1));

        verify(contactMapper).deleteStale(11L, CUTOFF);
    }

    @Test
    void doesNotDeleteWhileChunksAreStillMissing() {
        // 丢片时宁可留脏数据，也不能把号主的通讯录删掉一半
        when(contactMapper.countBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(1);

        sink().handle(chunk(0, 5, true, 1));

        verify(contactMapper, never()).deleteStale(anyLong(), anyLong());
    }

    @Test
    void doesNotDeleteWhenProtocolMarkedSnapshotIncomplete() {
        when(contactMapper.countBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(2);

        sink().handle(chunk(1, 2, false, 1));

        verify(contactMapper, never()).deleteStale(anyLong(), anyLong());
    }

    @Test
    void recordsPartialStatusWhenSnapshotIncomplete() {
        when(contactMapper.countBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(2);

        sink().handle(chunk(1, 2, false, 1));

        assertThat(capturedSyncState().getSyncStatus())
                .isEqualTo(AccountContactSync.STATUS_PARTIAL);
    }

    @Test
    void recordsSyncingStatusWhileIncomplete() {
        when(contactMapper.countBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(1);

        sink().handle(chunk(0, 5, true, 1));

        assertThat(capturedSyncState().getSyncStatus())
                .isEqualTo(AccountContactSync.STATUS_SYNCING);
    }

    @Test
    void writesCountsOnlyWhenSnapshotIsComplete() {
        // 半路回写会让账号筛选读到偏小的 contact_named_num
        when(contactMapper.countBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(1);

        sink().handle(chunk(0, 5, true, 1));

        verify(accountProfileService, never())
                .updateContactNamedNum(anyLong(), anyInt(), anyLong());
    }

    @Test
    void writesCountsOnCompletion() {
        when(contactMapper.countBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(2);
        when(contactMapper.countNamedBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(2);

        sink().handle(chunk(1, 2, true, 2));

        verify(accountProfileService).updateContactNamedNum(eq(11L), eq(2), eq(CUTOFF));
    }

    @Test
    void countsWholeSnapshotNotJustTheLastChunk() {
        // 回写的是整份快照的计数，不是最后一片的；用本片的 namedNum 会把好友数写成个位数
        when(contactMapper.countBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(1200);
        when(contactMapper.countNamedBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(900);

        sink().handle(chunk(1, 1200, true, 2));

        verify(accountProfileService).updateContactNamedNum(eq(11L), eq(900), eq(CUTOFF));
        AccountContactSync state = capturedSyncState();
        assertThat(state.getContactNum()).isEqualTo(1200);
        assertThat(state.getNamedNum()).isEqualTo(900);
    }

    @Test
    void recordsProtocolCutoffAsLastSyncedAt() {
        // last_synced_at 第一次表达「数据有多新」，不能再写 armada 自己的 now
        when(contactMapper.countBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(1);

        sink().handle(chunk(1, 1, true, 1));

        assertThat(capturedSyncState().getLastSyncedAt()).isEqualTo(CUTOFF);
    }

    @Test
    void neverCallsBatchInsertForEmptyChunk() {
        // 空片是合法的（这个号没有联系人），但 foreach 会生成空 VALUES
        when(contactMapper.countBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(0);

        sink().handle(chunk(0, 0, true, 0));

        verify(contactMapper, never()).upsertBatch(any());
    }

    @Test
    void emptySnapshotStillClearsLeftovers() {
        // 「这个号一个联系人都没有」必须能把历史残留清掉
        when(contactMapper.countBySyncedAt(eq(11L), eq(CUTOFF))).thenReturn(0);

        sink().handle(chunk(0, 0, true, 0));

        verify(contactMapper).deleteStale(11L, CUTOFF);
    }
}
