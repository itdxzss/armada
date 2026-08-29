package com.armada.hyperlink.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.hyperlink.task.mapper.HyperlinkTaskAnalysisExportMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkAttributionQuery;
import com.armada.hyperlink.task.model.dto.HyperlinkVisitTrendQuery;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.vo.HyperlinkVisitTrendVO;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
import com.armada.marketing.export.model.entity.MarketingTaskExportJob;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** H6 导出只创建 shared contract 异步作业，不退化为同步 10 万行响应。 */
class HyperlinkTaskAnalysisExportServiceTest {
    private HyperlinkTaskAnalysisExportMapper exportMapper;
    private HyperlinkTaskAnalysisService analysisService;
    private HyperlinkTaskAuditPort auditPort;
    private HyperlinkTaskAnalysisExportService service;
    private final AuthPrincipal principal = new AuthPrincipal(19, 7, "owner", "Owner",
            "t7", "Tenant 7", List.of("owner"), List.of(
            "tenant:hyperlink_task:export",
            "tenant:hyperlink_task:attribution_sensitive"));

    @BeforeEach
    void setUp() {
        exportMapper = mock(HyperlinkTaskAnalysisExportMapper.class);
        HyperlinkTaskMapper taskMapper = mock(HyperlinkTaskMapper.class);
        HyperlinkTaskRecipientMapper recipientMapper = mock(HyperlinkTaskRecipientMapper.class);
        analysisService = mock(HyperlinkTaskAnalysisService.class);
        auditPort = mock(HyperlinkTaskAuditPort.class);
        when(taskMapper.selectById(11)).thenReturn(new HyperlinkTask());
        doAnswer(invocation -> {
            MarketingTaskExportJob job = invocation.getArgument(0);
            job.setId(101L);
            return 1;
        }).when(exportMapper).insert(any());
        service = new HyperlinkTaskAnalysisExportService(exportMapper, taskMapper, recipientMapper,
                analysisService, new ObjectMapper(), auditPort,
                "/private/tmp/hyperlink-h6-export-test");
        TenantContext.set(7L);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void attributionCreationReturnsPendingJobAndPersistsNormalizedSnapshot() {
        HyperlinkAttributionQuery query = new HyperlinkAttributionQuery();
        query.setRecipientPhone(" 5511 ");
        query.setSortOrder("asc");

        var created = service.createAttribution(11, query, principal);

        assertThat(created.id()).isEqualTo(101);
        assertThat(created.exportType()).isEqualTo("ATTRIBUTION");
        assertThat(created.status()).isEqualTo("PENDING");
        assertThat(created.downloadReady()).isFalse();
        ArgumentCaptor<MarketingTaskExportJob> captor =
                ArgumentCaptor.forClass(MarketingTaskExportJob.class);
        verify(exportMapper).insert(captor.capture());
        assertThat(captor.getValue().getCountryIso2sJson())
                .contains("5511").doesNotContain("pageSize");
        assertThat(captor.getValue().getSnapshotAt()).isPositive();
        verify(auditPort).requireAvailable();
        verify(auditPort).record(any());
    }

    @Test
    void trendCreationFreezesSmallSeriesInsideTheJobPayload() {
        HyperlinkVisitTrendVO frozen = new HyperlinkVisitTrendVO("72h", "30m",
                "UNAVAILABLE_CUMULATIVE_ONLY",
                new HyperlinkVisitTrendVO.Summary(1, 10, 1L, 2L, 2L, 1, 2, 2),
                List.of(new HyperlinkVisitTrendVO.SeriesItem(2, 3, 1, 1, 10, null)),
                List.of(), List.of());
        when(analysisService.visitTrend(anyLong(), any())).thenReturn(frozen);
        HyperlinkVisitTrendQuery query = new HyperlinkVisitTrendQuery();
        query.setRange("72h");

        var created = service.createVisitTrend(11, query, principal);

        assertThat(created.exportType()).isEqualTo("VISIT_TREND");
        ArgumentCaptor<MarketingTaskExportJob> captor =
                ArgumentCaptor.forClass(MarketingTaskExportJob.class);
        verify(exportMapper).insert(captor.capture());
        assertThat(captor.getValue().getCountryIso2sJson())
                .contains("UNAVAILABLE_CUMULATIVE_ONLY").contains("series");
    }
}
