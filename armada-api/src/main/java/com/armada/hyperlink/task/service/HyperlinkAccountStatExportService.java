package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.export.HyperlinkAccountStatsCsvWriter;
import com.armada.hyperlink.task.mapper.HyperlinkTaskExportJobMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountStatFilterDTO;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskExportJobEntity;
import com.armada.hyperlink.task.model.query.HyperlinkAccountStatCriteria;
import com.armada.hyperlink.task.model.query.HyperlinkAccountStatExportPayload;
import com.armada.hyperlink.task.model.vo.HyperlinkAccountStatRow;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskExportFile;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskExportJobVO;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** H5 发信账号维度统计异步导出作业服务。 */
@Service
public class HyperlinkAccountStatExportService {

    private static final Logger log = LoggerFactory.getLogger(HyperlinkAccountStatExportService.class);
    private static final String MODE = "HYPERLINK_ACCOUNT_STATS";
    private static final String PENDING = "PENDING";
    private static final String SUCCESS = "SUCCESS";
    private static final int BATCH_SIZE = 500;
    private static final long LEASE_MILLIS = 30 * 60 * 1000L;
    private static final long FILE_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000L;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Shanghai"));
    private static final TypeReference<List<Long>> LONG_LIST = new TypeReference<>() { };

    private final HyperlinkTaskExportJobMapper jobMapper;
    private final HyperlinkAccountStatQueryService queryService;
    private final HyperlinkAccountStatCriteriaFactory criteriaFactory;
    private final HyperlinkAccountStatsCsvWriter csvWriter;
    private final HyperlinkTaskAuditPort auditPort;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Path storageRoot;

    @Autowired
    public HyperlinkAccountStatExportService(HyperlinkTaskExportJobMapper jobMapper,
            HyperlinkAccountStatQueryService queryService,
            HyperlinkAccountStatCriteriaFactory criteriaFactory,
            HyperlinkAccountStatsCsvWriter csvWriter,
            HyperlinkTaskAuditPort auditPort,
            ObjectMapper objectMapper,
            @Value("${armada.hyperlink.export.storage-dir:/app/data/hyperlink-exports}") String storageDir) {
        this(jobMapper, queryService, criteriaFactory, csvWriter, auditPort, objectMapper,
                Clock.systemUTC(), Path.of(storageDir));
    }

    HyperlinkAccountStatExportService(HyperlinkTaskExportJobMapper jobMapper,
            HyperlinkAccountStatQueryService queryService,
            HyperlinkAccountStatCriteriaFactory criteriaFactory,
            HyperlinkAccountStatsCsvWriter csvWriter,
            HyperlinkTaskAuditPort auditPort, ObjectMapper objectMapper,
            Clock clock, Path storageRoot) {
        this.jobMapper = jobMapper;
        this.queryService = queryService;
        this.criteriaFactory = criteriaFactory;
        this.csvWriter = csvWriter;
        this.auditPort = auditPort;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
    }

    @Transactional
    public HyperlinkTaskExportJobVO createAccountStatsJob(long taskId,
            HyperlinkAccountStatFilterDTO request, AuthPrincipal principal) {
        requirePrincipal(principal);
        queryService.requireTask(taskId);
        long now = clock.millis();
        HyperlinkAccountStatCriteria criteria = criteriaFactory.export(taskId, request, 0, BATCH_SIZE, now);
        HyperlinkAccountStatExportPayload payload = payload(criteria);
        String payloadJson = writeJson(payload);
        String requestHash = sha256(MODE + '|' + taskId + '|' + payloadJson);
        auditPort.requireAvailable();

        HyperlinkTaskExportJobEntity job = newJob(taskId, principal, now, payloadJson, requestHash);
        try {
            jobMapper.insert(job);
        } catch (DuplicateKeyException ex) {
            HyperlinkTaskExportJobEntity active = jobMapper.selectActive(
                    principal.tenantId(), principal.userId(), requestHash);
            if (active != null) {
                return toVO(active, now);
            }
            throw new BusinessException(ErrorCode.CONFLICT, "已有导出作业正在生成，请稍后再试");
        }
        auditPort.record(new HyperlinkTaskAuditPort.AuditEvent(
                "hyperlink-account-stats-export:" + job.getId(),
                HyperlinkTaskAuditPort.Action.ACCOUNT_STATS_EXPORT,
                principal.tenantId(), principal.userId(), taskId, now));
        return toVO(job, now);
    }

    public HyperlinkTaskExportJobVO getJob(long id, AuthPrincipal principal) {
        HyperlinkTaskExportJobEntity job = requireJob(id, principal);
        return toVO(job, clock.millis());
    }

    public HyperlinkTaskExportFile getDownload(long id, AuthPrincipal principal) {
        HyperlinkTaskExportJobEntity job = requireJob(id, principal);
        long now = clock.millis();
        if (!SUCCESS.equals(job.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "导出文件尚未生成完成");
        }
        if (job.getExpiresAt() != null && job.getExpiresAt() <= now) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出文件已过期，请重新导出");
        }
        Path file = resolveStorageKey(job.getStorageKey());
        if (!Files.isRegularFile(file)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出文件不存在，请重新导出");
        }
        return new HyperlinkTaskExportFile(file, job.getFileName(), job.getContentType(),
                job.getFileSize() == null ? fileSize(file) : job.getFileSize());
    }

    public void processPendingJobs(int limit) {
        long now = clock.millis();
        cleanupExpired(now);
        jobMapper.markExhausted(now, "导出处理多次中断，请重新发起导出");
        List<HyperlinkTaskExportJobEntity> jobs = jobMapper.selectProcessable(
                now, Math.max(1, Math.min(limit, 10)));
        for (HyperlinkTaskExportJobEntity job : jobs) {
            String token = UUID.randomUUID().toString();
            if (jobMapper.claim(job.getTenantId(), job.getId(), now,
                    now + LEASE_MILLIS, token) == 1) {
                processClaimed(job, token);
            }
        }
    }

    private void processClaimed(HyperlinkTaskExportJobEntity job, String token) {
        Long previousTenant = TenantContext.get();
        Path temporary = null;
        Path target = null;
        boolean published = false;
        try {
            TenantContext.set(job.getTenantId());
            long taskId = readTaskId(job.getTaskIdsJson());
            queryService.requireTask(taskId);
            HyperlinkAccountStatExportPayload payload = readPayload(job.getCountryIso2sJson());
            Path tenantDirectory = storageRoot.resolve(Long.toString(job.getTenantId())).normalize();
            ensureWithinStorage(tenantDirectory);
            Files.createDirectories(tenantDirectory);
            String storageKey = job.getTenantId() + "/" + job.getId() + "-" + token + ".csv";
            target = resolveStorageKey(storageKey);
            temporary = tenantDirectory.resolve(job.getId() + "-" + token + ".csv.part").normalize();
            int rowCount = writeAccountStats(temporary, taskId, payload, job, token);
            moveAtomically(temporary, target);
            temporary = null;
            published = completeJob(job, token, storageKey, target, taskId, rowCount);
        } catch (BusinessException ex) {
            log.warn("超链账号统计导出失败 tenantId={} jobId={} code={} message={}",
                    job.getTenantId(), job.getId(), ex.getCode(), ex.getMessage());
            markFailed(job, token, ex.getMessage());
        } catch (RuntimeException | IOException ex) {
            log.error("超链账号统计导出失败 tenantId={} jobId={}", job.getTenantId(), job.getId(), ex);
            markFailed(job, token, "导出失败，请重新操作");
        } finally {
            deleteQuietly(temporary);
            if (!published) {
                deleteQuietly(target);
            }
            TenantContext.clear();
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            }
        }
    }

    private int writeAccountStats(Path path, long taskId, HyperlinkAccountStatExportPayload payload,
            HyperlinkTaskExportJobEntity job, String token) throws IOException {
        HyperlinkAccountStatFilterDTO filter = filter(payload);
        int offset = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            csvWriter.writeHeader(writer);
            while (true) {
                HyperlinkAccountStatCriteria criteria = criteriaFactory.export(
                        taskId, filter, offset, BATCH_SIZE, job.getSnapshotAt());
                List<HyperlinkAccountStatRow> rows = queryService.loadBatch(criteria);
                for (HyperlinkAccountStatRow row : rows) {
                    csvWriter.writeItem(writer, queryService.toItem(row, job.getSnapshotAt()));
                }
                offset += rows.size();
                if (rows.size() < BATCH_SIZE) {
                    return offset;
                }
                renew(job, token);
            }
        }
    }

    private boolean completeJob(HyperlinkTaskExportJobEntity job, String token, String storageKey,
            Path target, long taskId, int rowCount) throws IOException {
        long finishedAt = clock.millis();
        job.setClaimToken(token);
        job.setStorageKey(storageKey);
        job.setFileName("hyperlink-account-stats-" + taskId + '-'
                + FILE_TIME.format(Instant.ofEpochMilli(job.getSnapshotAt())) + ".csv");
        job.setContentType(HyperlinkAccountStatsCsvWriter.CONTENT_TYPE);
        job.setFileSize(Files.size(target));
        job.setDetailRowCount(rowCount);
        job.setFinishedAt(finishedAt);
        job.setExpiresAt(finishedAt + FILE_TTL_MILLIS);
        if (jobMapper.markSuccess(job) != 1) {
            Files.deleteIfExists(target);
            return false;
        }
        return true;
    }

    private void renew(HyperlinkTaskExportJobEntity job, String token) {
        long now = clock.millis();
        if (jobMapper.renew(job.getTenantId(), job.getId(), token,
                now, now + LEASE_MILLIS) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "导出作业已被其他 Worker 接管");
        }
    }

    private void cleanupExpired(long now) {
        for (HyperlinkTaskExportJobEntity job : jobMapper.selectExpired(now, 20)) {
            try {
                Files.deleteIfExists(resolveStorageKey(job.getStorageKey()));
                jobMapper.markExpired(job.getTenantId(), job.getId(), job.getStorageKey(), now);
            } catch (RuntimeException | IOException ex) {
                log.warn("超链导出过期文件清理失败 tenantId={} jobId={}",
                        job.getTenantId(), job.getId(), ex);
            }
        }
    }

    private HyperlinkTaskExportJobEntity requireJob(long id, AuthPrincipal principal) {
        requirePrincipal(principal);
        if (id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "导出作业 ID 无效");
        }
        HyperlinkTaskExportJobEntity job = jobMapper.selectByIdForUser(id, principal.userId());
        if (job == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出作业不存在");
        }
        return job;
    }

    private static HyperlinkTaskExportJobEntity newJob(long taskId, AuthPrincipal principal,
            long now, String payloadJson, String requestHash) {
        HyperlinkTaskExportJobEntity job = new HyperlinkTaskExportJobEntity();
        job.setTenantId(principal.tenantId());
        job.setCreatedBy(principal.userId());
        job.setExportMode(MODE);
        job.setTaskIdsJson("[" + taskId + "]");
        job.setCountryIso2sJson(payloadJson);
        job.setRequestHash(requestHash);
        job.setStatus(PENDING);
        job.setSnapshotAt(now);
        job.setAttemptCount(0);
        job.setDetailRowCount(0);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        return job;
    }

    private static HyperlinkAccountStatExportPayload payload(HyperlinkAccountStatCriteria value) {
        return new HyperlinkAccountStatExportPayload(value.startAt(), value.endAt(),
                value.senderCountryIso2(), value.successRateMin(), value.successRateMax(),
                value.sortField(), value.sortOrder());
    }

    private static HyperlinkAccountStatFilterDTO filter(HyperlinkAccountStatExportPayload value) {
        HyperlinkAccountStatFilterDTO filter = new HyperlinkAccountStatFilterDTO();
        filter.setStartAt(value.startAt());
        filter.setEndAt(value.endAt());
        filter.setSenderCountryIso2(value.senderCountryIso2());
        filter.setSuccessRateMin(value.successRateMin());
        filter.setSuccessRateMax(value.successRateMax());
        filter.setSortField(value.sortField());
        filter.setSortOrder(value.sortOrder());
        return filter;
    }

    private HyperlinkTaskExportJobVO toVO(HyperlinkTaskExportJobEntity job, long now) {
        boolean expired = SUCCESS.equals(job.getStatus())
                && job.getExpiresAt() != null && job.getExpiresAt() <= now;
        String status = expired ? "EXPIRED" : job.getStatus();
        return new HyperlinkTaskExportJobVO(job.getId(), "ACCOUNT_STATS", status,
                job.getSnapshotAt(), job.getFileName(), zero(job.getDetailRowCount()),
                job.getErrorMessage(), job.getCreatedAt(), job.getFinishedAt(),
                SUCCESS.equals(status));
    }

    private void markFailed(HyperlinkTaskExportJobEntity job, String token, String message) {
        jobMapper.markFailed(job.getTenantId(), job.getId(), token,
                truncate(message, 500), clock.millis());
    }

    private long readTaskId(String json) {
        try {
            List<Long> ids = objectMapper.readValue(json, LONG_LIST);
            if (ids.size() != 1 || ids.get(0) == null || ids.get(0) <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION, "导出任务参数损坏，请重新导出");
            }
            return ids.get(0);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "导出任务参数损坏，请重新导出");
        }
    }

    private HyperlinkAccountStatExportPayload readPayload(String json) {
        try {
            return objectMapper.readValue(json, HyperlinkAccountStatExportPayload.class);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "导出筛选参数损坏，请重新导出");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "导出筛选序列化失败");
        }
    }

    private Path resolveStorageKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出文件不存在，请重新导出");
        }
        Path path = storageRoot.resolve(key).normalize();
        ensureWithinStorage(path);
        return path;
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

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static void requirePrincipal(AuthPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.AUTH_INVALID);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 原始作业失败优先；残留 .part 由运维按后缀清理。
        }
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出文件不可读取，请重新导出");
        }
    }

    private static String truncate(String value, int maxLength) {
        String text = value == null || value.isBlank() ? "导出失败，请重新操作" : value.trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private static int zero(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
