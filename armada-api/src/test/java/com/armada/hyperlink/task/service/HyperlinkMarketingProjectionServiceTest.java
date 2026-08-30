package com.armada.hyperlink.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.hyperlink.task.mapper.HyperlinkMarketingStatMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HyperlinkMarketingProjectionServiceTest {

    @Test
    void recentHourProjectionUsesTwoShanghaiBucketsAndEveryTenant() {
        HyperlinkMarketingStatMapper mapper = mock(HyperlinkMarketingStatMapper.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-30T01:35:00Z"), ZoneOffset.UTC);
        when(mapper.selectProjectionTenantIds(anyLong(), anyLong())).thenReturn(List.of(7L, 8L));

        new HyperlinkMarketingProjectionService(mapper, clock).rebuildRecentHours();

        ArgumentCaptor<Long> starts = ArgumentCaptor.forClass(Long.class);
        verify(mapper, times(4)).upsertHourlyBucket(anyLong(), starts.capture(),
                anyLong(), anyLong());
        assertThat(starts.getAllValues()).containsOnly(
                Instant.parse("2026-08-30T00:00:00Z").toEpochMilli(),
                Instant.parse("2026-08-30T01:00:00Z").toEpochMilli());
    }

    @Test
    void retainedWindowDeletesEveryExpiredBatch() {
        HyperlinkMarketingStatMapper mapper = mock(HyperlinkMarketingStatMapper.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-30T01:35:00Z"), ZoneOffset.UTC);
        when(mapper.selectProjectionTenantIds(anyLong(), anyLong())).thenReturn(List.of());
        when(mapper.deleteDailyBefore(anyInt(), anyInt())).thenReturn(5_000, 5_000, 12);
        when(mapper.deleteHourlyBefore(anyLong(), anyInt())).thenReturn(5_000, 1);

        new HyperlinkMarketingProjectionService(mapper, clock).rebuildRetainedWindows();

        verify(mapper, times(3)).deleteDailyBefore(anyInt(), anyInt());
        verify(mapper, times(2)).deleteHourlyBefore(anyLong(), anyInt());
    }
}
