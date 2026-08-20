package com.armada.marketing.export.service;

import com.armada.marketing.export.mapper.MarketingTaskExportMapper;
import com.armada.marketing.export.model.dto.MarketingTaskExportRequestDTO;
import com.armada.marketing.export.model.entity.MarketingTaskExportJob;
import com.armada.marketing.export.model.vo.MarketingTaskCountryEntryExportRow;
import com.armada.marketing.export.model.vo.MarketingTaskGroupExportRow;
import com.armada.marketing.export.service.impl.MarketingTaskExportServiceImpl;
import com.armada.marketing.export.service.impl.MarketingTaskWhatsAppMemberProvider;
import com.armada.marketing.export.writer.MarketingTaskExportWorkbookWriter;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.enums.MarketingBusinessType;
import com.armada.platform.country.model.vo.CountryOptionVO;
import com.armada.platform.country.service.CountryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.security.AuthPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.TaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketingTaskExportServiceImplTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-29T03:20:00Z"), ZoneId.of("Asia/Shanghai"));

    @Mock
    private MarketingTaskExportMapper mapper;
    @Mock
    private CountryService countryService;
    @Mock
    private MarketingTaskExportWorkbookWriter workbookWriter;
    @Mock
    private MarketingTaskWhatsAppMemberProvider whatsAppMemberProvider;
    @Mock
    private TaskScheduler taskScheduler;

    @TempDir
    private Path tempDir;

    private MarketingTaskExportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MarketingTaskExportServiceImpl(
                mapper,
                countryService,
                workbookWriter,
                new ObjectMapper(),
                new MarketingTaskExportRuntime(
                        taskScheduler, FIXED_CLOCK, tempDir, whatsAppMemberProvider));
        lenient().when(countryService.activePhonePrefixResolver()).thenReturn(phone -> null);
    }

    @Test
    void createCountryEntryJobUsesServerSnapshotAndNormalizesSelection() {
        when(mapper.selectTasksByIds(List.of(7L, 9L)))
                .thenReturn(List.of(ordinaryTask(9L), ordinaryTask(7L)));
        when(countryService.requireActiveOption("ID", false))
                .thenReturn(new CountryOptionVO(
                        "IN", "ID", "印度尼西亚", "Indonesia", "+62", "🇮🇩", false, "ASIA"));
        when(countryService.requireActiveOption("MY", false))
                .thenReturn(new CountryOptionVO(
                        "MY", "MY", "马来西亚", "Malaysia", "+60", "🇲🇾", false, "ASIA"));
        when(mapper.insertJob(any(MarketingTaskExportJob.class))).thenAnswer(invocation -> {
            MarketingTaskExportJob job = invocation.getArgument(0);
            job.setId(88L);
            return 1;
        });

        var result = service.createJob(
                new MarketingTaskExportRequestDTO(
                        "COUNTRY_ENTRY",
                        List.of(9L, 7L, 9L),
                        List.of("my", "ID", "MY")),
                principal());

        ArgumentCaptor<MarketingTaskExportJob> captor = ArgumentCaptor.forClass(MarketingTaskExportJob.class);
        verify(mapper).insertJob(captor.capture());
        MarketingTaskExportJob job = captor.getValue();
        assertThat(job.getTenantId()).isEqualTo(3L);
        assertThat(job.getCreatedBy()).isEqualTo(5L);
        assertThat(job.getExportMode()).isEqualTo("COUNTRY_ENTRY");
        assertThat(job.getTaskIdsJson()).isEqualTo("[7,9]");
        assertThat(job.getCountryIso2sJson()).isEqualTo("[\"ID\",\"MY\"]");
        assertThat(job.getSnapshotAt()).isEqualTo(FIXED_CLOCK.millis());
        assertThat(job.getStatus()).isEqualTo("PENDING");
        assertThat(result.id()).isEqualTo(88L);
        assertThat(result.snapshotAt()).isEqualTo(FIXED_CLOCK.millis());
        verify(countryService).requireActiveOption("ID", false);
        verify(countryService).requireActiveOption("MY", false);
    }

    @Test
    void createJobRejectsOversizedRawTaskSelectionBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.createJob(
                new MarketingTaskExportRequestDTO(
                        "FULL",
                        Collections.nCopies(101, 9L),
                        List.of()),
                principal()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("单次最多导出 100 个营销任务");

        verify(mapper, never()).selectTasksByIds(any());
        verify(mapper, never()).insertJob(any());
    }

    @Test
    void createJobRejectsOversizedRawCountrySelectionBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.createJob(
                new MarketingTaskExportRequestDTO(
                        "COUNTRY_ENTRY",
                        List.of(9L),
                        Collections.nCopies(250, "ID")),
                principal()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("单次最多选择 249 个国家或地区");

        verify(countryService, never()).requireActiveOption(anyString(), eq(false));
        verify(mapper, never()).selectTasksByIds(any());
        verify(mapper, never()).insertJob(any());
    }

    @Test
    void createJobRejectsInvalidIso2BeforeCountryLookup() {
        assertThatThrownBy(() -> service.createJob(
                new MarketingTaskExportRequestDTO(
                        "COUNTRY_ENTRY",
                        List.of(9L),
                        List.of("IDN")),
                principal()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("国家或地区编码必须为 2 位 ISO2");

        verify(countryService, never()).requireActiveOption(anyString(), eq(false));
        verify(mapper, never()).selectTasksByIds(any());
        verify(mapper, never()).insertJob(any());
    }

    @Test
    void createJobRejectsGroupPullTaskWithoutWritingJob() {
        MarketingTask task = ordinaryTask(9L);
        task.setBusinessType(MarketingBusinessType.GROUP_PULL.code());
        when(mapper.selectTasksByIds(List.of(9L))).thenReturn(List.of(task));

        assertThatThrownBy(() -> service.createJob(
                new MarketingTaskExportRequestDTO("FULL", List.of(9L), List.of()),
                principal()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("仅支持导出普通营销任务");

        verify(mapper, never()).insertJob(any());
    }

    @Test
    void createJobReturnsExistingActiveJobWhenRequestIsDuplicated() {
        when(mapper.selectTasksByIds(List.of(9L))).thenReturn(List.of(ordinaryTask(9L)));
        MarketingTaskExportJob existing = new MarketingTaskExportJob();
        existing.setId(77L);
        existing.setTenantId(3L);
        existing.setCreatedBy(5L);
        existing.setExportMode("FULL");
        existing.setStatus("PROCESSING");
        existing.setSnapshotAt(FIXED_CLOCK.millis() - 1_000L);
        existing.setSummaryRowCount(0);
        existing.setDetailRowCount(0);
        existing.setCreatedAt(FIXED_CLOCK.millis() - 1_000L);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("duplicate"))
                .when(mapper).insertJob(any(MarketingTaskExportJob.class));
        when(mapper.selectActiveJob(eq(3L), eq(5L), anyString())).thenReturn(existing);

        var result = service.createJob(
                new MarketingTaskExportRequestDTO("FULL", List.of(9L), List.of()),
                principal());

        assertThat(result.id()).isEqualTo(77L);
        assertThat(result.status()).isEqualTo("PROCESSING");
    }

    @Test
    void createJobRejectsDifferentRequestWhileCreatorHasActiveJob() {
        when(mapper.selectTasksByIds(List.of(9L))).thenReturn(List.of(ordinaryTask(9L)));
        org.mockito.Mockito.doThrow(new DuplicateKeyException("creator has active job"))
                .when(mapper).insertJob(any(MarketingTaskExportJob.class));
        when(mapper.selectActiveJob(eq(3L), eq(5L), anyString())).thenReturn(null);

        assertThatThrownBy(() -> service.createJob(
                new MarketingTaskExportRequestDTO("FULL", List.of(9L), List.of()),
                principal()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("已有导出作业正在生成，请完成后再发起导出");
    }

    @Test
    void getJobScopesReadToAuthenticatedCreator() {
        MarketingTaskExportJob existing = new MarketingTaskExportJob();
        existing.setId(77L);
        existing.setCreatedBy(5L);
        existing.setExportMode("FULL");
        existing.setStatus("PENDING");
        existing.setSnapshotAt(FIXED_CLOCK.millis());
        existing.setSummaryRowCount(0);
        existing.setDetailRowCount(0);
        existing.setCreatedAt(FIXED_CLOCK.millis());
        when(mapper.selectJobByIdForUser(77L, 5L)).thenReturn(existing);

        assertThat(service.getJob(77L, principal()).id()).isEqualTo(77L);
        verify(mapper).selectJobByIdForUser(77L, 5L);
    }

    @Test
    void processPendingJobsFailsExpiredJobsThatExhaustedRetriesBeforeScanning() {
        when(mapper.selectExpiredFiles(FIXED_CLOCK.millis(), 20)).thenReturn(List.of());
        when(mapper.selectProcessableJobs(FIXED_CLOCK.millis(), 2)).thenReturn(List.of());

        service.processPendingJobs(2);

        verify(mapper).markExhaustedJobs(
                FIXED_CLOCK.millis(), "导出处理多次中断，请重新发起导出");
        verify(mapper).selectProcessableJobs(FIXED_CLOCK.millis(), 2);
    }

    @Test
    void processPendingJobsUsesFreshLeaseTimeForEachClaim() {
        long scanAt = 1_000L;
        long secondClaimAt = scanAt + 30L * 60 * 1000 + 1;
        Clock advancingClock = mock(Clock.class);
        when(advancingClock.millis()).thenReturn(scanAt, scanAt, secondClaimAt);
        MarketingTaskExportJob first = pendingJob(88L, "FULL");
        MarketingTaskExportJob second = pendingJob(89L, "FULL");
        when(mapper.selectExpiredFiles(scanAt, 20)).thenReturn(List.of());
        when(mapper.selectProcessableJobs(scanAt, 2)).thenReturn(List.of(first, second));
        when(mapper.claimJob(anyLong(), anyLong(), anyLong(), anyLong(), anyString())).thenReturn(0);
        MarketingTaskExportServiceImpl advancingService = new MarketingTaskExportServiceImpl(
                mapper,
                countryService,
                workbookWriter,
                new ObjectMapper(),
                new MarketingTaskExportRuntime(
                        taskScheduler, advancingClock, tempDir, whatsAppMemberProvider));

        advancingService.processPendingJobs(2);

        verify(mapper).claimJob(
                eq(3L), eq(88L), eq(scanAt), eq(scanAt + 30L * 60 * 1000), anyString());
        verify(mapper).claimJob(
                eq(3L), eq(89L), eq(secondClaimAt),
                eq(secondClaimAt + 30L * 60 * 1000), anyString());
    }

    @Test
    void processPendingJobsDeletesExpiredFileAndClearsStorageMetadata() throws Exception {
        Path tenantDirectory = Files.createDirectories(tempDir.resolve("3"));
        Path expiredFile = tenantDirectory.resolve("88.xlsx");
        Files.write(expiredFile, new byte[] {1, 2, 3});
        MarketingTaskExportJob expired = new MarketingTaskExportJob();
        expired.setId(88L);
        expired.setTenantId(3L);
        expired.setStorageKey("3/88.xlsx");
        when(mapper.selectExpiredFiles(FIXED_CLOCK.millis(), 20)).thenReturn(List.of(expired));
        when(mapper.selectProcessableJobs(FIXED_CLOCK.millis(), 1)).thenReturn(List.of());

        service.processPendingJobs(1);

        assertThat(expiredFile).doesNotExist();
        verify(mapper).clearExpiredStorage(3L, 88L, "3/88.xlsx", FIXED_CLOCK.millis());
    }

    @Test
    void processPendingFullJobStreamsWorkbookAndPublishesWithClaimToken() throws Exception {
        MarketingTaskExportJob pending = pendingJob(88L, "FULL");
        when(mapper.selectExpiredFiles(FIXED_CLOCK.millis(), 20)).thenReturn(List.of());
        when(mapper.selectProcessableJobs(FIXED_CLOCK.millis(), 1)).thenReturn(List.of(pending));
        when(mapper.claimJob(
                eq(3L), eq(88L), eq(FIXED_CLOCK.millis()),
                eq(FIXED_CLOCK.millis() + 30 * 60 * 1000L), anyString()))
                .thenReturn(1);
        when(mapper.selectTasksByIds(List.of(9L))).thenReturn(List.of(ordinaryTask(9L)));
        when(mapper.renewJobLease(eq(3L), eq(88L), anyString(),
                eq(FIXED_CLOCK.millis()), eq(FIXED_CLOCK.millis() + 30 * 60 * 1000L)))
                .thenReturn(1);
        when(countryService.activePhonePrefixResolver()).thenReturn(phone -> null);
        when(workbookWriter.writeFull(
                any(Path.class),
                any(MarketingTaskExportWorkbookWriter.FullRowSource.class),
                eq(FIXED_CLOCK.instant()),
                eq(FIXED_CLOCK.instant())))
                .thenAnswer(invocation -> {
                    Path output = invocation.getArgument(0);
                    MarketingTaskExportWorkbookWriter.FullRowSource rows =
                            invocation.getArgument(1);
                    rows.forEach(ignored -> { }, ignored -> { });
                    Files.write(output, new byte[] {1, 2, 3});
                    return new MarketingTaskExportWorkbookWriter.WriteResult(1, 0);
                });
        when(mapper.markJobSuccess(any(MarketingTaskExportJob.class)))
                .thenReturn(1);

        service.processPendingJobs(1);

        ArgumentCaptor<String> claimedToken = ArgumentCaptor.forClass(String.class);
        verify(mapper).claimJob(
                eq(3L), eq(88L), eq(FIXED_CLOCK.millis()),
                eq(FIXED_CLOCK.millis() + 30 * 60 * 1000L), claimedToken.capture());
        verify(whatsAppMemberProvider).streamFull(
                argThat(request -> request.tenantId().equals(3L)
                        && request.taskIds().equals(List.of(9L))
                        && request.snapshotAt() == FIXED_CLOCK.millis()),
                any(MarketingTaskWhatsAppMemberProvider.FullOutput.class));

        ArgumentCaptor<MarketingTaskExportJob> completedJob =
                ArgumentCaptor.forClass(MarketingTaskExportJob.class);
        verify(mapper).markJobSuccess(completedJob.capture());
        assertThat(completedJob.getValue().getClaimToken()).isEqualTo(claimedToken.getValue());
        assertThat(completedJob.getValue().getStorageKey()).isEqualTo(
                "3/88-" + claimedToken.getValue() + ".xlsx");
        assertThat(completedJob.getValue().getFileName()).isEqualTo("营销任务全量数据_20260729_112000.xlsx");
        assertThat(completedJob.getValue().getContentType())
                .isEqualTo(MarketingTaskExportWorkbookWriter.CONTENT_TYPE);
        assertThat(completedJob.getValue().getFileSize()).isEqualTo(3L);
        assertThat(completedJob.getValue().getSummaryRowCount()).isEqualTo(1);
        assertThat(completedJob.getValue().getDetailRowCount()).isZero();
        assertThat(completedJob.getValue().getFinishedAt()).isEqualTo(FIXED_CLOCK.millis());
        assertThat(completedJob.getValue().getExpiresAt())
                .isEqualTo(FIXED_CLOCK.millis() + 7L * 24 * 60 * 60 * 1000);
        assertThat(tempDir.resolve(completedJob.getValue().getStorageKey())).exists();
    }

    @Test
    void processPendingCountryJobResolvesSelectedCountryBeforeWritingRows() throws Exception {
        MarketingTaskExportJob pending = pendingJob(89L, "COUNTRY_ENTRY");
        pending.setCountryIso2sJson("[\"ID\"]");
        MarketingTaskCountryEntryExportRow sourceRow = new MarketingTaskCountryEntryExportRow();
        sourceRow.setActualPhone("628123456789");
        sourceRow.setCountryIso2("ID");
        sourceRow.setCountryName("印度尼西亚");
        sourceRow.setCountryPhonePrefix("+62");
        CountryOptionVO indonesia = new CountryOptionVO(
                "IN", "ID", "印度尼西亚", "Indonesia", "+62", "🇮🇩", false, "ASIA");
        MarketingTaskGroupExportRow sourceGroup = new MarketingTaskGroupExportRow();
        sourceGroup.setTaskId(9L);
        sourceGroup.setGroupMemberCount(18);
        List<MarketingTaskGroupExportRow> writtenGroups = new ArrayList<>();
        List<MarketingTaskCountryEntryExportRow> writtenRows = new ArrayList<>();

        when(mapper.selectExpiredFiles(FIXED_CLOCK.millis(), 20)).thenReturn(List.of());
        when(mapper.selectProcessableJobs(FIXED_CLOCK.millis(), 1)).thenReturn(List.of(pending));
        when(mapper.claimJob(
                eq(3L), eq(89L), eq(FIXED_CLOCK.millis()),
                eq(FIXED_CLOCK.millis() + 30 * 60 * 1000L), anyString()))
                .thenReturn(1);
        when(mapper.selectTasksByIds(List.of(9L))).thenReturn(List.of(ordinaryTask(9L)));
        when(mapper.renewJobLease(eq(3L), eq(89L), anyString(),
                eq(FIXED_CLOCK.millis()), eq(FIXED_CLOCK.millis() + 30 * 60 * 1000L)))
                .thenReturn(1);
        when(countryService.activePhonePrefixResolver()).thenReturn(phone -> indonesia);
        doAnswer(invocation -> {
            MarketingTaskWhatsAppMemberProvider.CountryOutput output =
                    invocation.getArgument(1);
            output.groupConsumer().accept(sourceGroup);
            output.countryConsumer().accept(sourceRow);
            return null;
        }).when(whatsAppMemberProvider).streamCountry(
                any(MarketingTaskWhatsAppMemberProvider.ExportRequest.class),
                any(MarketingTaskWhatsAppMemberProvider.CountryOutput.class));
        when(workbookWriter.writeCountryEntry(
                any(Path.class),
                any(MarketingTaskExportWorkbookWriter.CountryRowSource.class),
                eq(FIXED_CLOCK.instant()),
                eq(FIXED_CLOCK.instant())))
                .thenAnswer(invocation -> {
                    Path output = invocation.getArgument(0);
                    MarketingTaskExportWorkbookWriter.CountryRowSource rows =
                            invocation.getArgument(1);
                    rows.forEach(writtenGroups::add, writtenRows::add);
                    Files.write(output, new byte[] {1, 2, 3});
                    return new MarketingTaskExportWorkbookWriter.WriteResult(
                            writtenGroups.size(), writtenRows.size());
                });
        when(mapper.markJobSuccess(any(MarketingTaskExportJob.class)))
                .thenReturn(1);

        service.processPendingJobs(1);

        assertThat(writtenGroups).containsExactly(sourceGroup);
        assertThat(writtenRows).containsExactly(sourceRow);
        assertThat(sourceRow.getCountryName()).isEqualTo("印度尼西亚");
        assertThat(sourceRow.getCountryPhonePrefix()).isEqualTo("+62");
        verify(countryService).activePhonePrefixResolver();
        verify(whatsAppMemberProvider).streamCountry(
                argThat(request -> request.tenantId().equals(3L)
                        && request.taskIds().equals(List.of(9L))
                        && request.snapshotAt() == FIXED_CLOCK.millis()),
                any(MarketingTaskWhatsAppMemberProvider.CountryOutput.class));
        ArgumentCaptor<MarketingTaskExportJob> completedJob =
                ArgumentCaptor.forClass(MarketingTaskExportJob.class);
        verify(mapper).markJobSuccess(completedJob.capture());
        assertThat(completedJob.getValue().getFileName())
                .isEqualTo("营销任务按国家进群明细_20260729_112000.xlsx");
        assertThat(completedJob.getValue().getFileSize()).isEqualTo(3L);
        assertThat(completedJob.getValue().getSummaryRowCount()).isEqualTo(1);
        assertThat(completedJob.getValue().getDetailRowCount()).isEqualTo(1);
    }

    @Test
    void processPendingJobStopsWhenLeaseTokenIsNoLongerOwned() throws Exception {
        MarketingTaskExportJob pending = pendingJob(90L, "FULL");
        AtomicReference<Runnable> scheduledHeartbeat = new AtomicReference<>();
        ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
        when(mapper.selectExpiredFiles(FIXED_CLOCK.millis(), 20)).thenReturn(List.of());
        when(mapper.selectProcessableJobs(FIXED_CLOCK.millis(), 1)).thenReturn(List.of(pending));
        when(mapper.claimJob(
                eq(3L), eq(90L), eq(FIXED_CLOCK.millis()),
                eq(FIXED_CLOCK.millis() + 30 * 60 * 1000L), anyString()))
                .thenReturn(1);
        when(mapper.selectTasksByIds(List.of(9L))).thenReturn(List.of(ordinaryTask(9L)));
        when(mapper.renewJobLease(eq(3L), eq(90L), anyString(),
                eq(FIXED_CLOCK.millis()), eq(FIXED_CLOCK.millis() + 30 * 60 * 1000L)))
                .thenReturn(1, 0);
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), eq(Duration.ofMinutes(5))))
                .thenAnswer(invocation -> {
                    scheduledHeartbeat.set(invocation.getArgument(0));
                    return scheduledFuture;
                });
        when(countryService.activePhonePrefixResolver())
                .thenAnswer(invocation -> {
                    scheduledHeartbeat.get().run();
                    return (CountryService.PhonePrefixResolver) phone -> null;
                });
        when(workbookWriter.writeFull(
                any(Path.class),
                any(MarketingTaskExportWorkbookWriter.FullRowSource.class),
                eq(FIXED_CLOCK.instant()),
                eq(FIXED_CLOCK.instant())))
                .thenReturn(new MarketingTaskExportWorkbookWriter.WriteResult(1, 0));

        service.processPendingJobs(1);

        verify(countryService).activePhonePrefixResolver();
        verify(mapper, never()).markJobSuccess(any(MarketingTaskExportJob.class));
        verify(mapper).markJobFailed(
                eq(3L), eq(90L), anyString(),
                eq("导出作业已被其他实例接管"), eq(FIXED_CLOCK.millis()));
        verify(scheduledFuture).cancel(false);
    }

    @Test
    void processCountryJobUsesProductErrorWhenNoSuccessfulJoinRowsExist() throws Exception {
        MarketingTaskExportJob pending = pendingJob(91L, "COUNTRY_ENTRY");
        pending.setCountryIso2sJson("[\"ID\"]");
        when(mapper.selectExpiredFiles(FIXED_CLOCK.millis(), 20)).thenReturn(List.of());
        when(mapper.selectProcessableJobs(FIXED_CLOCK.millis(), 1)).thenReturn(List.of(pending));
        when(mapper.claimJob(
                eq(3L), eq(91L), eq(FIXED_CLOCK.millis()),
                eq(FIXED_CLOCK.millis() + 30 * 60 * 1000L), anyString()))
                .thenReturn(1);
        when(mapper.selectTasksByIds(List.of(9L))).thenReturn(List.of(ordinaryTask(9L)));
        when(mapper.renewJobLease(eq(3L), eq(91L), anyString(),
                eq(FIXED_CLOCK.millis()), eq(FIXED_CLOCK.millis() + 30 * 60 * 1000L)))
                .thenReturn(1);
        when(workbookWriter.writeCountryEntry(
                any(Path.class),
                any(MarketingTaskExportWorkbookWriter.CountryRowSource.class),
                eq(FIXED_CLOCK.instant()),
                eq(FIXED_CLOCK.instant())))
                .thenReturn(new MarketingTaskExportWorkbookWriter.WriteResult(0, 0));

        service.processPendingJobs(1);

        verify(mapper).markJobFailed(
                eq(3L), eq(91L), anyString(),
                eq("所选任务的 WhatsApp 群成员中没有符合国家条件的数据"),
                eq(FIXED_CLOCK.millis()));
    }

    private static MarketingTask ordinaryTask(Long id) {
        MarketingTask task = new MarketingTask();
        task.setId(id);
        task.setBusinessType(MarketingBusinessType.ORDINARY.code());
        task.setTaskName("任务-" + id);
        return task;
    }

    private static MarketingTaskExportJob pendingJob(Long id, String mode) {
        MarketingTaskExportJob job = new MarketingTaskExportJob();
        job.setId(id);
        job.setTenantId(3L);
        job.setCreatedBy(5L);
        job.setExportMode(mode);
        job.setTaskIdsJson("[9]");
        job.setCountryIso2sJson("[]");
        job.setStatus("PENDING");
        job.setSnapshotAt(FIXED_CLOCK.millis());
        job.setAttemptCount(0);
        job.setCreatedAt(FIXED_CLOCK.millis());
        job.setUpdatedAt(FIXED_CLOCK.millis());
        return job;
    }

    private static AuthPrincipal principal() {
        return new AuthPrincipal(5L, 3L, "tester", "测试", "t3", "租户3", List.of(), List.of());
    }

}
