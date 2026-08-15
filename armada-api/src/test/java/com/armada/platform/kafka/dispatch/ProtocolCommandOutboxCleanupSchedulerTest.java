package com.armada.platform.kafka.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.mapper.ProtocolCommandOutboxMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 已发送命令保留期清理的批次收敛与保留期口径测试。 */
class ProtocolCommandOutboxCleanupSchedulerTest {

    @Test
    void drainsUntilABatchComesBackShortSoBacklogIsNotSpreadAcrossRuns() {
        ProtocolCommandOutboxMapper mapper = mock(ProtocolCommandOutboxMapper.class);
        // 前两批删满，第三批不足即认为当轮已删干净。
        when(mapper.deleteSentBefore(anyLong(), anyInt())).thenReturn(10_000, 10_000, 137);

        new ProtocolCommandOutboxCleanupScheduler(mapper, 7, 10_000)
                .purgeExpiredSentCommands();

        verify(mapper, times(3)).deleteSentBefore(anyLong(), anyInt());
    }

    @Test
    void stopsAfterASingleEmptyBatchWhenThereIsNothingToPurge() {
        ProtocolCommandOutboxMapper mapper = mock(ProtocolCommandOutboxMapper.class);
        when(mapper.deleteSentBefore(anyLong(), anyInt())).thenReturn(0);

        new ProtocolCommandOutboxCleanupScheduler(mapper, 7, 10_000)
                .purgeExpiredSentCommands();

        verify(mapper, times(1)).deleteSentBefore(anyLong(), anyInt());
    }

    @Test
    void cutoffIsDerivedFromRetentionDaysSoMissedRunsStillPurgeEverythingExpired() {
        ProtocolCommandOutboxMapper mapper = mock(ProtocolCommandOutboxMapper.class);
        when(mapper.deleteSentBefore(anyLong(), anyInt())).thenReturn(0);
        long before = System.currentTimeMillis();

        new ProtocolCommandOutboxCleanupScheduler(mapper, 7, 10_000)
                .purgeExpiredSentCommands();

        // 保留期起点按本轮当前时间倒推，不依赖上次清理进度，
        // 因此停机或漏跑之后的第一次运行仍会选中全部超期行。
        ArgumentCaptor<Long> cutoff = ArgumentCaptor.forClass(Long.class);
        verify(mapper).deleteSentBefore(cutoff.capture(), anyInt());
        long expected = before - Duration.ofDays(7).toMillis();
        assertThat(cutoff.getValue())
                .isGreaterThanOrEqualTo(expected)
                .isLessThanOrEqualTo(expected + Duration.ofMinutes(1).toMillis());
    }

    @Test
    void batchSizeAndRetentionAreClampedToUsableValues() {
        ProtocolCommandOutboxMapper mapper = mock(ProtocolCommandOutboxMapper.class);
        when(mapper.deleteSentBefore(anyLong(), anyInt())).thenReturn(0);

        new ProtocolCommandOutboxCleanupScheduler(mapper, 0, 0).purgeExpiredSentCommands();

        // 配置写成 0 时不能退化成"删除全部"或"单批 0 行导致死循环"。
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Long> cutoff = ArgumentCaptor.forClass(Long.class);
        verify(mapper).deleteSentBefore(cutoff.capture(), limit.capture());
        assertThat(limit.getValue()).isEqualTo(1);
        assertThat(cutoff.getValue())
                .isLessThanOrEqualTo(System.currentTimeMillis() - Duration.ofDays(1).toMillis());
    }

    @Test
    void doesNotTouchTheMapperBeforeTheScheduledRun() {
        ProtocolCommandOutboxMapper mapper = mock(ProtocolCommandOutboxMapper.class);

        new ProtocolCommandOutboxCleanupScheduler(mapper, 7, 10_000);

        verifyNoInteractions(mapper);
    }
}
