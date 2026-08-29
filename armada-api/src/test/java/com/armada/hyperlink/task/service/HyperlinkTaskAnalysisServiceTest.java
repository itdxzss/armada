package com.armada.hyperlink.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkAttributionQuery;
import com.armada.hyperlink.task.model.dto.HyperlinkVisitTrendQuery;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime;
import com.armada.hyperlink.task.model.vo.HyperlinkVisitBucketRow;
import com.armada.hyperlink.task.model.vo.HyperlinkBanReasonRow;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** H6 脱敏、趋势公式与不伪造 PV 分桶的纯服务测试。 */
class HyperlinkTaskAnalysisServiceTest {
    private HyperlinkTaskMapper taskMapper;
    private HyperlinkTaskRuntimeMapper runtimeMapper;
    private HyperlinkTaskRecipientMapper recipientMapper;
    private HyperlinkTaskAccountUsageMapper usageMapper;
    private HyperlinkTaskAnalysisService service;
    private HyperlinkTaskAuditPort auditPort;

    @BeforeEach
    void setUp() {
        taskMapper = mock(HyperlinkTaskMapper.class);
        runtimeMapper = mock(HyperlinkTaskRuntimeMapper.class);
        recipientMapper = mock(HyperlinkTaskRecipientMapper.class);
        usageMapper = mock(HyperlinkTaskAccountUsageMapper.class);
        auditPort = mock(HyperlinkTaskAuditPort.class);
        service = new HyperlinkTaskAnalysisService(taskMapper, runtimeMapper, recipientMapper,
                usageMapper, auditPort);
        when(taskMapper.selectById(11)).thenReturn(new HyperlinkTask());
    }

    @Test
    void masksSensitiveFieldsAndReportsMaskedFieldNamesWithoutHidingDerivedFacts() {
        HyperlinkTaskRecipient row = new HyperlinkTaskRecipient();
        row.setId(1L);
        row.setRecipientPhoneSnapshot("5511999");
        row.setClickCount(2);
        row.setFirstVisitIpAddress(new byte[]{127, 0, 0, 1});
        row.setFirstVisitUserAgent("raw-ua");
        row.setFirstVisitDevice("mobile");
        row.setFirstVisitAt(100L);
        row.setLastVisitAt(200L);
        when(recipientMapper.countClicked(eq(11L), isNull(), isNull())).thenReturn(1L);
        when(recipientMapper.selectClickedPage(eq(11L), isNull(), isNull(), eq("desc"),
                eq(0), eq(20))).thenReturn(List.of(row));

        var masked = service.attribution(11, new HyperlinkAttributionQuery(), false, 7, 19)
                .list().get(0);
        assertThat(masked.ip()).isNull();
        assertThat(masked.userAgent()).isNull();
        assertThat(masked.maskedFields()).containsExactly("ip", "userAgent");
        assertThat(masked.device()).isEqualTo("mobile");
        verifyNoInteractions(auditPort);

        var visible = service.attribution(11, new HyperlinkAttributionQuery(), true, 7, 19)
                .list().get(0);
        assertThat(visible.ip()).isEqualTo("127.0.0.1");
        assertThat(visible.userAgent()).isEqualTo("raw-ua");
        assertThat(visible.maskedFields()).isEmpty();
        verify(auditPort).requireAvailable();
        verify(auditPort).record(any());
    }

    @Test
    void builds144HalfHourBucketsFor72HoursAndKeepsHistoricalPvBucketsUnavailable() {
        HyperlinkTaskRuntime runtime = new HyperlinkTaskRuntime();
        runtime.setFirstVisitAt(1_000L);
        runtime.setStartedAt(500L);
        runtime.setSuccessNum(10L);
        runtime.setClickTotal(9L);
        when(runtimeMapper.selectByTaskId(11)).thenReturn(runtime);
        HyperlinkVisitBucketRow first = bucket(0, 2);
        HyperlinkVisitBucketRow last = bucket(143, 1);
        when(recipientMapper.selectVisitUvBuckets(eq(11L), eq(1_000L),
                eq(1_000L + 72 * 3_600_000L), eq(1_800_000L)))
                .thenReturn(List.of(first, last));
        HyperlinkVisitTrendQuery query = new HyperlinkVisitTrendQuery();
        query.setRange("72h");
        query.setGranularity("30m");

        var trend = service.visitTrend(11, query);

        assertThat(trend.series()).hasSize(144);
        assertThat(trend.series().get(0).newUv()).isEqualTo(2);
        assertThat(trend.series().get(143).cumulativeUv()).isEqualTo(3);
        assertThat(trend.series()).allMatch(item -> item.pv() == null);
        assertThat(trend.pvBucketMode()).isEqualTo("UNAVAILABLE_CUMULATIVE_ONLY");
        assertThat(trend.summary().pvTotal()).isEqualTo(9);
        assertThat(trend.summary().pvPerUv()).isEqualTo(3.0);
        assertThat(trend.summary().clickRate()).isEqualTo(30.0);
        assertThat(trend.topPeaks()).extracting(item -> item.newUv()).containsExactly(2L, 1L);
    }

    @Test
    void banStatsKeepUnknownFallbackAndCompetitorReasonNote() {
        HyperlinkBanReasonRow offline = ban("ACCOUNT_OFFLINE", 2);
        HyperlinkBanReasonRow unknown = ban("未知原因", 1);
        when(usageMapper.selectBanReasonStats(11)).thenReturn(List.of(offline, unknown));

        var result = service.banStats(11);

        assertThat(result.invalidAccountCount()).isEqualTo(3);
        assertThat(result.stats().get(0).note()).isEqualTo("中途强制被掐掉，封号");
        assertThat(result.stats().get(0).percentage()).isEqualTo(66.7);
        assertThat(result.stats().get(1).reason()).isEqualTo("未知原因");
        assertThat(result.stats().get(1).note()).isNull();
    }

    private HyperlinkVisitBucketRow bucket(int number, long count) {
        HyperlinkVisitBucketRow row = new HyperlinkVisitBucketRow();
        row.setBucketNo(number);
        row.setNewUv(count);
        return row;
    }

    private HyperlinkBanReasonRow ban(String reason, long count) {
        HyperlinkBanReasonRow row = new HyperlinkBanReasonRow();
        row.setReason(reason);
        row.setAccountCount(count);
        return row;
    }
}
