package com.armada.marketing.export.service.impl;

import com.armada.marketing.export.mapper.MarketingTaskExportMapper;
import com.armada.marketing.export.model.dto.MarketingTaskExportRequestDTO;
import com.armada.marketing.export.model.entity.MarketingTaskExportJob;
import com.armada.marketing.export.model.vo.MarketingTaskCountryEntryExportRow;
import com.armada.marketing.export.model.vo.MarketingTaskExportFile;
import com.armada.marketing.export.model.vo.MarketingTaskExportJobVO;
import com.armada.marketing.export.model.vo.MarketingTaskGroupExportRow;
import com.armada.marketing.export.model.vo.MarketingTaskGroupMemberExportRow;
import com.armada.marketing.export.service.MarketingTaskExportService;
import com.armada.marketing.export.service.MarketingTaskExportRuntime;
import com.armada.marketing.export.writer.MarketingTaskExportWorkbookWriter;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.enums.MarketingBusinessType;
import com.armada.platform.country.model.vo.CountryOptionVO;
import com.armada.platform.country.service.CountryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/** 基于数据库作业和本地持久化目录实现普通营销任务异步导出。 */
@Service
public class MarketingTaskExportServiceImpl implements MarketingTaskExportService {

    private static final Logger log = LoggerFactory.getLogger(MarketingTaskExportServiceImpl.class);
    private static final String MODE_COUNTRY_ENTRY = "COUNTRY_ENTRY";
    private static final String MODE_FULL = "FULL";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final int MAX_TASK_IDS = 100;
    private static final int MAX_COUNTRY_ISO2S = 249;
    private static final long LEASE_MILLIS = 30 * 60 * 1000L;
    private static final long LEASE_RENEW_INTERVAL_MILLIS = 5 * 60 * 1000L;
    private static final long FILE_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FILE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(BUSINESS_ZONE);
    private static final TypeReference<List<Long>> LONG_LIST = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final MarketingTaskExportMapper mapper;
    private final CountryService countryService;
    private final MarketingTaskExportWorkbookWriter workbookWriter;
    private final ObjectMapper objectMapper;
    private final TaskScheduler taskScheduler;
    private final Clock clock;
    private final Path storageRoot;

    @Autowired
    public MarketingTaskExportServiceImpl(
            MarketingTaskExportMapper mapper,
            CountryService countryService,
            MarketingTaskExportWorkbookWriter workbookWriter,
            ObjectMapper objectMapper,
            MarketingTaskExportRuntime runtime) {
        this.mapper = mapper;
        this.countryService = countryService;
        this.workbookWriter = workbookWriter;
        this.objectMapper = objectMapper;
        this.taskScheduler = runtime.taskScheduler();
        this.clock = runtime.clock();
        this.storageRoot = runtime.storageRoot();
    }

    @Override
    public MarketingTaskExportJobVO createJob(MarketingTaskExportRequestDTO request, AuthPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.AUTH_INVALID);
        }
        String mode = normalizeMode(request == null ? null : request.exportMode());
        List<Long> taskIds = normalizeTaskIds(request == null ? null : request.taskIds());
        List<String> countryIso2s = normalizeCountries(
                mode, request == null ? null : request.countryIso2s());
        validateTasks(taskIds);

        long now = clock.millis();
        MarketingTaskExportJob job = new MarketingTaskExportJob();
        job.setTenantId(principal.tenantId());
        job.setCreatedBy(principal.userId());
        job.setExportMode(mode);
        job.setTaskIdsJson(writeJson(taskIds));
        job.setCountryIso2sJson(writeJson(countryIso2s));
        job.setRequestHash(sha256(mode + '|' + job.getTaskIdsJson() + '|' + job.getCountryIso2sJson()));
        job.setStatus(STATUS_PENDING);
        job.setSnapshotAt(now);
        job.setAttemptCount(0);
        job.setSummaryRowCount(0);
        job.setDetailRowCount(0);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        try {
            mapper.insertJob(job);
        } catch (DuplicateKeyException ex) {
            MarketingTaskExportJob existing = mapper.selectActiveJob(
                    principal.tenantId(), principal.userId(), job.getRequestHash());
            if (existing != null) {
                return toVO(existing);
            }
            throw new BusinessException(ErrorCode.CONFLICT, "已有导出作业正在生成，请完成后再发起导出");
        }
        return toVO(job);
    }

    @Override
    public MarketingTaskExportJobVO getJob(Long id, AuthPrincipal principal) {
        return toVO(requireJob(id, principal));
    }

    @Override
    public MarketingTaskExportFile getDownload(Long id, AuthPrincipal principal) {
        MarketingTaskExportJob job = requireJob(id, principal);
        long now = clock.millis();
        if (!STATUS_SUCCESS.equals(job.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "导出文件尚未生成完成");
        }
        if (job.getExpiresAt() != null && job.getExpiresAt() <= now) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出文件已过期，请重新导出");
        }
        Path file = resolveStorageKey(job.getStorageKey());
        if (!Files.isRegularFile(file)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出文件不存在，请重新导出");
        }
        return new MarketingTaskExportFile(
                file,
                job.getFileName(),
                job.getContentType(),
                job.getFileSize() == null ? fileSize(file) : job.getFileSize());
    }

    @Override
    public void processPendingJobs(int limit) {
        long now = clock.millis();
        cleanupExpiredFiles(now);
        mapper.markExhaustedJobs(now, "导出处理多次中断，请重新发起导出");
        List<MarketingTaskExportJob> jobs = mapper.selectProcessableJobs(now, Math.max(1, Math.min(limit, 10)));
        for (MarketingTaskExportJob job : jobs) {
            String claimToken = UUID.randomUUID().toString();
            if (mapper.claimJob(
                    job.getTenantId(), job.getId(), now, now + LEASE_MILLIS, claimToken) == 1) {
                processClaimedJob(job, claimToken);
            }
        }
    }

    private void cleanupExpiredFiles(long now) {
        List<MarketingTaskExportJob> expiredJobs = mapper.selectExpiredFiles(now, 20);
        if (expiredJobs == null || expiredJobs.isEmpty()) {
            return;
        }
        for (MarketingTaskExportJob job : expiredJobs) {
            try {
                Path file = resolveStorageKey(job.getStorageKey());
                Files.deleteIfExists(file);
                mapper.clearExpiredStorage(
                        job.getTenantId(), job.getId(), job.getStorageKey(), now);
            } catch (RuntimeException | IOException ex) {
                log.warn("营销任务导出过期文件清理失败 tenantId={} jobId={}",
                        job.getTenantId(), job.getId(), ex);
            }
        }
    }

    private void processClaimedJob(MarketingTaskExportJob job, String claimToken) {
        Long previousTenant = TenantContext.get();
        LeaseHeartbeat heartbeat = new LeaseHeartbeat(job, claimToken);
        Path temporaryFile = null;
        Path targetFile = null;
        boolean published = false;
        try {
            TenantContext.set(job.getTenantId());
            List<Long> taskIds = readJson(job.getTaskIdsJson(), LONG_LIST);
            List<MarketingTask> tasks = mapper.selectTasksByIds(taskIds);
            requireSameTaskSelection(taskIds, tasks);
            heartbeat.start();

            Instant snapshotAt = Instant.ofEpochMilli(job.getSnapshotAt());
            Instant generatedAt = clock.instant();
            Path tenantDirectory = storageRoot.resolve(String.valueOf(job.getTenantId())).normalize();
            ensureWithinStorage(tenantDirectory);
            Files.createDirectories(tenantDirectory);
            String storageKey = job.getTenantId() + "/" + job.getId() + "-" + claimToken + ".xlsx";
            targetFile = resolveStorageKey(storageKey);
            temporaryFile = tenantDirectory.resolve(
                    job.getId() + "-" + claimToken + ".xlsx.part").normalize();
            Files.deleteIfExists(temporaryFile);

            MarketingTaskExportWorkbookWriter.WriteResult writeResult;
            if (MODE_COUNTRY_ENTRY.equals(job.getExportMode())) {
                List<String> selectedCountries = readJson(job.getCountryIso2sJson(), STRING_LIST);
                writeResult = workbookWriter.writeCountryEntry(
                        temporaryFile,
                        consumer -> streamCountryRows(
                                job, taskIds, selectedCountries, heartbeat, consumer),
                        snapshotAt,
                        generatedAt);
                if (writeResult.detailRowCount() == 0) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION, "所选任务的 WhatsApp 群成员中没有符合国家条件的数据");
                }
            } else {
                CountryService.PhonePrefixResolver countryResolver =
                        countryService.activePhonePrefixResolver();
                writeResult = workbookWriter.writeFull(
                        temporaryFile,
                        consumer -> mapper.selectGroupRows(
                                job.getTenantId(), taskIds, job.getSnapshotAt(),
                                context -> {
                                    heartbeat.renewIfDue();
                                    consumer.accept(context.getResultObject());
                                }),
                        consumer -> streamGroupMemberRows(
                                job, taskIds, countryResolver, heartbeat, consumer),
                        snapshotAt,
                        generatedAt);
            }

            heartbeat.renewNow();
            moveAtomically(temporaryFile, targetFile);
            temporaryFile = null;
            long finishedAt = clock.millis();
            String fileName = fileName(job.getExportMode(), generatedAt);
            job.setClaimToken(claimToken);
            job.setStorageKey(storageKey);
            job.setFileName(fileName);
            job.setContentType(MarketingTaskExportWorkbookWriter.CONTENT_TYPE);
            job.setFileSize(Files.size(targetFile));
            job.setSummaryRowCount(writeResult.summaryRowCount());
            job.setDetailRowCount(writeResult.detailRowCount());
            job.setFinishedAt(finishedAt);
            job.setExpiresAt(finishedAt + FILE_TTL_MILLIS);
            int completed = mapper.markJobSuccess(job);
            if (completed != 1) {
                Files.deleteIfExists(targetFile);
            } else {
                published = true;
            }
        } catch (BusinessException ex) {
            markFailed(job, claimToken, ex.getMessage());
        } catch (RuntimeException | IOException ex) {
            log.error("营销任务导出失败 tenantId={} jobId={}", job.getTenantId(), job.getId(), ex);
            markFailed(job, claimToken, "导出失败，请重新操作");
        } finally {
            heartbeat.close();
            deleteQuietly(temporaryFile);
            if (!published) {
                deleteQuietly(targetFile);
            }
            TenantContext.clear();
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            }
        }
    }

    private void streamCountryRows(MarketingTaskExportJob job,
                                   List<Long> taskIds,
                                   List<String> selectedCountryIso2s,
                                   LeaseHeartbeat heartbeat,
                                   Consumer<MarketingTaskCountryEntryExportRow> consumer) {
        Set<String> selected = new LinkedHashSet<>(selectedCountryIso2s);
        CountryService.PhonePrefixResolver countryResolver = countryService.activePhonePrefixResolver();
        mapper.selectCountryEntryRows(job.getTenantId(), taskIds, job.getSnapshotAt(), context -> {
            heartbeat.renewIfDue();
            MarketingTaskCountryEntryExportRow row = context.getResultObject();
            CountryOptionVO country = countryResolver.resolve(row.getActualPhone());
            if (country != null && selected.contains(country.iso2())) {
                row.setCountryName(country.nameZh());
                row.setCountryPhonePrefix(country.phonePrefix());
                consumer.accept(row);
            }
        });
    }

    private void streamGroupMemberRows(
            MarketingTaskExportJob job,
            List<Long> taskIds,
            CountryService.PhonePrefixResolver countryResolver,
            LeaseHeartbeat heartbeat,
            Consumer<MarketingTaskGroupMemberExportRow> consumer) {
        mapper.selectGroupMemberRows(job.getTenantId(), taskIds, job.getSnapshotAt(), context -> {
            heartbeat.renewIfDue();
            MarketingTaskGroupMemberExportRow row = context.getResultObject();
            CountryOptionVO country = countryResolver.resolve(row.getMemberPhone());
            if (country != null) {
                row.setCountryName(country.nameZh());
            }
            consumer.accept(row);
        });
    }

    private void validateTasks(List<Long> taskIds) {
        List<MarketingTask> tasks = mapper.selectTasksByIds(taskIds);
        requireSameTaskSelection(taskIds, tasks);
        if (tasks.stream().anyMatch(task -> task.getBusinessType() == null
                || task.getBusinessType() != MarketingBusinessType.ORDINARY.code())) {
            throw new BusinessException(ErrorCode.VALIDATION, "仅支持导出普通营销任务");
        }
    }

    private static void requireSameTaskSelection(List<Long> taskIds, List<MarketingTask> tasks) {
        Set<Long> found = tasks == null ? Set.of() : tasks.stream()
                .map(MarketingTask::getId)
                .collect(Collectors.toSet());
        if (found.size() != taskIds.size() || !found.containsAll(taskIds)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部分营销任务不存在或无权访问");
        }
    }

    private List<String> normalizeCountries(String mode, Collection<String> source) {
        if (source != null && source.size() > MAX_COUNTRY_ISO2S) {
            throw new BusinessException(ErrorCode.VALIDATION, "单次最多选择 249 个国家或地区");
        }
        if (MODE_FULL.equals(mode)) {
            return List.of();
        }
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        LinkedHashSet<String> countries = new LinkedHashSet<>();
        if (source != null) {
            for (String value : source) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                String iso2 = value.trim().toUpperCase(Locale.ROOT);
                if (iso2.length() != 2
                        || iso2.charAt(0) < 'A' || iso2.charAt(0) > 'Z'
                        || iso2.charAt(1) < 'A' || iso2.charAt(1) > 'Z') {
                    throw new BusinessException(ErrorCode.VALIDATION, "国家或地区编码必须为 2 位 ISO2");
                }
                if (!requested.add(iso2)) {
                    continue;
                }
                CountryOptionVO option = countryService.requireActiveOption(iso2, false);
                countries.add(option.iso2());
            }
        }
        if (countries.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "按国家导出至少选择一个国家或地区");
        }
        List<String> normalized = new ArrayList<>(countries);
        normalized.sort(String::compareTo);
        return normalized;
    }

    private static List<Long> normalizeTaskIds(Collection<Long> source) {
        if (source != null && source.size() > MAX_TASK_IDS) {
            throw new BusinessException(ErrorCode.VALIDATION, "单次最多导出 100 个营销任务");
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (source != null) {
            for (Long id : source) {
                if (id != null && id > 0) {
                    ids.add(id);
                }
            }
        }
        if (ids.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "营销任务 ID 列表不能为空");
        }
        if (ids.size() > MAX_TASK_IDS) {
            throw new BusinessException(ErrorCode.VALIDATION, "单次最多导出 100 个营销任务");
        }
        List<Long> normalized = new ArrayList<>(ids);
        normalized.sort(Long::compareTo);
        return normalized;
    }

    private static String normalizeMode(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择导出模式");
        }
        String mode = value.trim().toUpperCase(Locale.ROOT);
        if (!MODE_COUNTRY_ENTRY.equals(mode) && !MODE_FULL.equals(mode)) {
            throw new BusinessException(ErrorCode.VALIDATION, "不支持的导出模式");
        }
        return mode;
    }

    private MarketingTaskExportJob requireJob(Long id, AuthPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.AUTH_INVALID);
        }
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "导出作业 ID 无效");
        }
        MarketingTaskExportJob job = mapper.selectJobByIdForUser(id, principal.userId());
        if (job == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出作业不存在");
        }
        return job;
    }

    private static MarketingTaskExportJobVO toVO(MarketingTaskExportJob job) {
        return new MarketingTaskExportJobVO(
                job.getId(), job.getExportMode(), job.getStatus(), job.getSnapshotAt(), job.getFileName(),
                zero(job.getSummaryRowCount()), zero(job.getDetailRowCount()), job.getErrorMessage(),
                job.getCreatedAt(), job.getFinishedAt(), STATUS_SUCCESS.equals(job.getStatus()));
    }

    private void markFailed(MarketingTaskExportJob job, String claimToken, String message) {
        mapper.markJobFailed(
                job.getTenantId(), job.getId(), claimToken, truncate(message, 500), clock.millis());
    }

    private Path resolveStorageKey(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出文件不存在，请重新导出");
        }
        Path resolved = storageRoot.resolve(storageKey).normalize();
        ensureWithinStorage(resolved);
        return resolved;
    }

    private void ensureWithinStorage(Path path) {
        if (!path.toAbsolutePath().normalize().startsWith(storageRoot)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "非法导出文件路径");
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件清理失败不覆盖作业原始错误，后续由运维按 .part 后缀清理。
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "导出范围序列化失败");
        }
    }

    private <T> T readJson(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "导出作业参数损坏，请重新导出");
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String fileName(String mode, Instant generatedAt) {
        String prefix = MODE_COUNTRY_ENTRY.equals(mode) ? "营销任务按国家进群明细" : "营销任务全量数据";
        return prefix + "_" + FILE_TIME_FORMAT.format(generatedAt) + ".xlsx";
    }

    private static int zero(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出文件不可读取，请重新导出");
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "导出失败，请重新操作";
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    /** 当前导出 Worker 的短租约心跳；claim token 变化后旧 Worker 立即停止。 */
    private final class LeaseHeartbeat implements AutoCloseable {
        private final MarketingTaskExportJob job;
        private final String claimToken;
        private volatile BusinessException ownershipFailure;
        private volatile boolean closed;
        private ScheduledFuture<?> scheduledFuture;
        private long nextRenewAt;

        private LeaseHeartbeat(MarketingTaskExportJob job, String claimToken) {
            this.job = job;
            this.claimToken = claimToken;
        }

        private void start() {
            renewNow();
            scheduledFuture = taskScheduler.scheduleAtFixedRate(
                    this::renewInBackground,
                    Duration.ofMillis(LEASE_RENEW_INTERVAL_MILLIS));
        }

        private void renewIfDue() {
            verifyOwnership();
            long now = clock.millis();
            if (now >= nextRenewAt) {
                renew(now);
            }
        }

        private void renewNow() {
            verifyOwnership();
            renew(clock.millis());
        }

        private void renewInBackground() {
            if (closed || ownershipFailure != null) {
                return;
            }
            try {
                renew(clock.millis());
            } catch (BusinessException ex) {
                ownershipFailure = ex;
            } catch (RuntimeException ex) {
                log.warn("营销任务导出心跳续租失败 tenantId={} jobId={}",
                        job.getTenantId(), job.getId(), ex);
            }
        }

        private void renew(long now) {
            int renewed = mapper.renewJobLease(
                    job.getTenantId(), job.getId(), claimToken, now, now + LEASE_MILLIS);
            if (renewed != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "导出作业已被其他实例接管");
            }
            nextRenewAt = now + LEASE_RENEW_INTERVAL_MILLIS;
        }

        private void verifyOwnership() {
            if (ownershipFailure != null) {
                throw ownershipFailure;
            }
        }

        @Override
        public void close() {
            closed = true;
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
            }
        }
    }
}
