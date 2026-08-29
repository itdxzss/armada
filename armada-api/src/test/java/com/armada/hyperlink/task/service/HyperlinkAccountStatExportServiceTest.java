package com.armada.hyperlink.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.armada.hyperlink.task.export.HyperlinkAccountStatsCsvWriter;
import com.armada.hyperlink.task.mapper.HyperlinkTaskExportJobMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountStatFilterDTO;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskExportJobEntity;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskExportJobVO;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
import com.armada.shared.security.AuthPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** ACCOUNT_STATS 作业创建必须冻结筛选、租户身份和审计事实。 */
class HyperlinkAccountStatExportServiceTest {

    @Test
    void createsAuditedPendingAccountStatsJobWithNormalizedFilterSnapshot() {
        HyperlinkTaskExportJobMapper jobs = mock(HyperlinkTaskExportJobMapper.class);
        HyperlinkAccountStatQueryService queries = mock(HyperlinkAccountStatQueryService.class);
        HyperlinkTaskAuditPort audit = mock(HyperlinkTaskAuditPort.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, HyperlinkTaskExportJobEntity.class).setId(91L);
            return 1;
        }).when(jobs).insert(any(HyperlinkTaskExportJobEntity.class));
        HyperlinkAccountStatExportService service = new HyperlinkAccountStatExportService(
                jobs, queries, new HyperlinkAccountStatCriteriaFactory(),
                new HyperlinkAccountStatsCsvWriter(), audit, new ObjectMapper(),
                Clock.fixed(Instant.ofEpochMilli(5_000L), ZoneOffset.UTC),
                Path.of("target/test-account-stat-exports"));
        HyperlinkAccountStatFilterDTO filter = new HyperlinkAccountStatFilterDTO();
        filter.setSenderCountryIso2("br");
        filter.setSuccessRateMin(new BigDecimal("75"));
        filter.setSortField("failedNum");
        filter.setSortOrder("asc");

        HyperlinkTaskExportJobVO job = service.createAccountStatsJob(
                11L, filter, new AuthPrincipal(3L, 7L, "u", "n", "t", "T", List.of(), List.of()));

        assertThat(job.id()).isEqualTo(91L);
        assertThat(job.exportType()).isEqualTo("ACCOUNT_STATS");
        assertThat(job.status()).isEqualTo("PENDING");
        assertThat(job.snapshotAt()).isEqualTo(5_000L);
        ArgumentCaptor<HyperlinkTaskExportJobEntity> jobCaptor =
                ArgumentCaptor.forClass(HyperlinkTaskExportJobEntity.class);
        verify(jobs).insert(jobCaptor.capture());
        assertThat(jobCaptor.getValue().getTenantId()).isEqualTo(7L);
        assertThat(jobCaptor.getValue().getCountryIso2sJson())
                .contains("\"senderCountryIso2\":\"BR\"", "\"successRateMin\":75");
        verify(audit).requireAvailable();
        ArgumentCaptor<HyperlinkTaskAuditPort.AuditEvent> auditCaptor =
                ArgumentCaptor.forClass(HyperlinkTaskAuditPort.AuditEvent.class);
        verify(audit).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action())
                .isEqualTo(HyperlinkTaskAuditPort.Action.ACCOUNT_STATS_EXPORT);
        assertThat(auditCaptor.getValue().tenantId()).isEqualTo(7L);
    }
}
