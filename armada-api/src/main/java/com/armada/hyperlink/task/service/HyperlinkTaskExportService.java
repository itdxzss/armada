package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskDetailMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskExportMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkRecipientExportPayload;
import com.armada.hyperlink.task.model.dto.HyperlinkRecipientExportRequestDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkRecipientQuery;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskExportJob;
import com.armada.hyperlink.task.model.vo.HyperlinkRecipientRow;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskExportFile;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskExportJobVO;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 收信人 CSV 导出适配器及公共超链导出作业外壳。 */
@Service
public class HyperlinkTaskExportService {

    private static final Logger log = LoggerFactory.getLogger(HyperlinkTaskExportService.class);
    private static final String TYPE_RECIPIENTS = "RECIPIENTS";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_EXPIRED = "EXPIRED";
    private static final int EXPORT_BATCH_SIZE = 2_000;
    private static final long LEASE_MILLIS = 30 * 60 * 1000L;
    private static final long FILE_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final DateTimeFormatter FILE_TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final HyperlinkTaskMapper taskMapper;
    private final HyperlinkTaskDetailMapper detailMapper;
    private final HyperlinkTaskExportMapper exportMapper;
    private final HyperlinkTaskDetailService detailService;
    private final HyperlinkTaskAuditPort auditPort;
    private final HyperlinkRecipientCsvWriter csvWriter;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Path storageRoot;

    public HyperlinkTaskExportService(
            HyperlinkTaskMapper taskMapper,
            HyperlinkTaskDetailMapper detailMapper,
            HyperlinkTaskExportMapper exportMapper,
            HyperlinkTaskDetailService detailService,
            HyperlinkTaskAuditPort auditPort,
            ObjectMapper objectMapper,
            @Value("${armada.hyperlink.export.storage-dir:/app/data/marketing-exports}")
            String storageDir) {
        this(taskMapper, detailMapper, exportMapper, detailService, auditPort,
                new HyperlinkRecipientCsvWriter(), objectMapper, Clock.systemUTC(),
                Path.of(storageDir));
    }

    HyperlinkTaskExportService(
            HyperlinkTaskMapper taskMapper,
            HyperlinkTaskDetailMapper detailMapper,
            HyperlinkTaskExportMapper exportMapper,
            HyperlinkTaskDetailService detailService,
            HyperlinkTaskAuditPort auditPort,
            HyperlinkRecipientCsvWriter csvWriter,
            ObjectMapper objectMapper,
            Clock clock,
            Path storageRoot) {
        this.taskMapper = taskMapper;
        this.detailMapper = detailMapper;
        this.exportMapper = exportMapper;
        this.detailService = detailService;
        this.auditPort = auditPort;
        this.csvWriter = csvWriter;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
    }

    @Transactional
    public HyperlinkTaskExportJobVO createRecipientJob(
            long taskId,
            HyperlinkRecipientExportRequestDTO request,
            AuthPrincipal principal) {
        requirePrincipal(principal);
        requireTask(taskId);
        HyperlinkRecipientQuery query = toQuery(request);
        detailService.normalize(taskId, query, false);
        HyperlinkRecipientExportPayload payload = new HyperlinkRecipientExportPayload(
                taskId, query.getPhone(), query.getRecipientCountryIso2(),
                query.getSenderCountryIso2(), query.getFailReason(),
                query.getSortField(), query.getSortOrder());
        String payloadJson = writeJson(payload);
        long now = clock.millis();
        auditPort.requireAvailable();

        HyperlinkTaskExportJob job = new HyperlinkTaskExportJob();
        job.setTenantId(principal.tenantId());
        job.setCreatedBy(principal.userId());
        job.setDataScopeMode("ALL");
        job.setExportType(TYPE_RECIPIENTS);
        job.setTaskIdsJson("[" + taskId + "]");
        job.setCountryIso2sJson("[]");
        job.setRequestPayloadJson(payloadJson);
        job.setRequestHash(sha256(TYPE_RECIPIENTS + '|' + taskId + '|' + payloadJson));
        job.setStatus(STATUS_PENDING);
        job.setSnapshotAt(now);
        job.setAttemptCount(0);
        job.setRowCount(0);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        try {
            exportMapper.insertJob(job);
        } catch (DuplicateKeyException ex) {
            HyperlinkTaskExportJob existing = exportMapper.selectActiveJob(
                    principal.tenantId(), principal.userId(), job.getRequestHash());
            if (existing != null) {
                return toVO(existing);
            }
            throw new BusinessException(ErrorCode.CONFLICT,
                    "已有导出作业正在生成，请完成后再发起导出");
        }
        auditPort.record(new HyperlinkTaskAuditPort.AuditEvent(
                "hyperlink-export:recipients:" + job.getId(),
                HyperlinkTaskAuditPort.Action.EXPORT_RECIPIENTS,
                principal.tenantId(), principal.userId(), taskId, now));
        return toVO(job);
    }

    public HyperlinkTaskExportJobVO getJob(long id, AuthPrincipal principal) {
        return toVO(requireJob(id, principal));
    }

    public HyperlinkTaskExportFile getDownload(long id, AuthPrincipal principal) {
        HyperlinkTaskExportJob job = requireJob(id, principal);
        if ("ATTRIBUTION".equals(job.getExportType())
                && (principal.permissions() == null
                    || !principal.permissions().contains(
                            "tenant:hyperlink_task:attribution_sensitive"))) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "无权下载敏感归因数据");
        }
        String effectiveStatus = effectiveStatus(job);
        if (STATUS_EXPIRED.equals(effectiveStatus)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出文件已过期，请重新导出");
        }
        if (!STATUS_SUCCESS.equals(effectiveStatus)) {
            throw new BusinessException(ErrorCode.CONFLICT, "导出文件尚未生成完成");
        }
        Path file = resolveStorageKey(job.getStorageKey());
        if (!Files.isRegularFile(file)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出文件不存在，请重新导出");
        }
        return new HyperlinkTaskExportFile(file, job.getFileName(), job.getContentType(),
                job.getFileSize() == null ? fileSize(file) : job.getFileSize());
    }

    public void processPendingRecipientJobs(int limit) {
        long now = clock.millis();
        cleanupExpiredFiles(now);
        exportMapper.markExhaustedRecipientJobs(now, "导出处理多次中断，请重新发起导出");
        List<HyperlinkTaskExportJob> jobs = exportMapper.selectProcessableRecipientJobs(
                now, Math.max(1, Math.min(limit, 10)));
        for (HyperlinkTaskExportJob job : jobs) {
            long claimAt = clock.millis();
            String claimToken = UUID.randomUUID().toString();
            int claimed = exportMapper.claimRecipientJob(
                    job.getTenantId(), job.getId(), claimAt,
                    claimAt + LEASE_MILLIS, claimToken);
            if (claimed == 1) {
                processClaimedJob(job, claimToken);
            }
        }
    }

    private void processClaimedJob(HyperlinkTaskExportJob job, String claimToken) {
        Long previousTenant = TenantContext.get();
        Path temporaryFile = null;
        Path targetFile = null;
        boolean published = false;
        try {
            TenantContext.set(job.getTenantId());
            HyperlinkRecipientExportPayload payload = objectMapper.readValue(
                    job.getRequestPayloadJson(), HyperlinkRecipientExportPayload.class);
            requireTask(payload.taskId());
            HyperlinkRecipientQuery query = new HyperlinkRecipientQuery();
            query.setPhone(payload.phone());
            query.setRecipientCountryIso2(payload.recipientCountryIso2());
            query.setSenderCountryIso2(payload.senderCountryIso2());
            query.setFailReason(payload.failReason());
            query.setSortField(payload.sortField());
            query.setSortOrder(payload.sortOrder());
            detailService.normalize(payload.taskId(), query, false);

            Path tenantDirectory = storageRoot.resolve(String.valueOf(job.getTenantId())).normalize();
            ensureWithinStorage(tenantDirectory);
            Files.createDirectories(tenantDirectory);
            String storageKey = job.getTenantId() + "/" + job.getId() + "-" + claimToken + ".csv";
            targetFile = resolveStorageKey(storageKey);
            temporaryFile = tenantDirectory.resolve(
                    job.getId() + "-" + claimToken + ".csv.part").normalize();
            Files.deleteIfExists(temporaryFile);

            int rowCount = writeCsv(job, claimToken, query, temporaryFile);
            moveAtomically(temporaryFile, targetFile);
            temporaryFile = null;
            long finishedAt = clock.millis();
            job.setClaimToken(claimToken);
            job.setStorageKey(storageKey);
            job.setFileName("hyperlink-recipients-" + payload.taskId() + '-'
                    + FILE_TIME_FORMAT.format(Instant.ofEpochMilli(finishedAt)) + ".csv");
            job.setContentType(HyperlinkRecipientCsvWriter.CONTENT_TYPE);
            job.setFileSize(Files.size(targetFile));
            job.setRowCount(rowCount);
            job.setFinishedAt(finishedAt);
            job.setExpiresAt(finishedAt + FILE_TTL_MILLIS);
            if (exportMapper.markRecipientJobSuccess(job) == 1) {
                published = true;
            }
        } catch (BusinessException ex) {
            log.warn("收信人导出业务失败 tenantId={} jobId={} code={} message={}",
                    job.getTenantId(), job.getId(), ex.getCode(), ex.getMessage());
            markFailed(job, claimToken, ex.getMessage());
        } catch (RuntimeException | IOException ex) {
            log.error("收信人导出失败 tenantId={} jobId={}",
                    job.getTenantId(), job.getId(), ex);
            markFailed(job, claimToken, "导出失败，请重新操作");
        } finally {
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

    private int writeCsv(
            HyperlinkTaskExportJob job,
            String claimToken,
            HyperlinkRecipientQuery query,
            Path temporaryFile) throws IOException {
        int count = 0;
        Long cursorId = null;
        try (BufferedWriter writer = Files.newBufferedWriter(
                temporaryFile, StandardCharsets.UTF_8)) {
            csvWriter.writeHeader(writer);
            while (true) {
                renewLease(job, claimToken);
                List<HyperlinkRecipientRow> batch = detailMapper.selectRecipientExportBatch(
                        query, job.getSnapshotAt(), cursorId, EXPORT_BATCH_SIZE);
                if (batch.isEmpty()) {
                    break;
                }
                for (HyperlinkRecipientRow row : batch) {
                    csvWriter.writeRow(writer, row);
                    count++;
                }
                cursorId = batch.get(batch.size() - 1).getId();
                if (batch.size() < EXPORT_BATCH_SIZE) {
                    break;
                }
            }
        }
        return count;
    }

    private void renewLease(HyperlinkTaskExportJob job, String claimToken) {
        long now = clock.millis();
        int renewed = exportMapper.renewRecipientJobLease(
                job.getTenantId(), job.getId(), claimToken, now, now + LEASE_MILLIS);
        if (renewed != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "导出作业已被其他实例接管");
        }
    }

    private void cleanupExpiredFiles(long now) {
        List<HyperlinkTaskExportJob> jobs = exportMapper.selectExpiredRecipientFiles(now, 20);
        for (HyperlinkTaskExportJob job : jobs) {
            try {
                Path file = resolveStorageKey(job.getStorageKey());
                Files.deleteIfExists(file);
                exportMapper.clearExpiredRecipientStorage(
                        job.getTenantId(), job.getId(), job.getStorageKey(), now);
            } catch (RuntimeException | IOException ex) {
                log.warn("收信人导出过期文件清理失败 tenantId={} jobId={}",
                        job.getTenantId(), job.getId(), ex);
            }
        }
    }

    private HyperlinkTaskExportJob requireJob(long id, AuthPrincipal principal) {
        requirePrincipal(principal);
        if (id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "导出作业 ID 无效");
        }
        HyperlinkTaskExportJob job = exportMapper.selectJobByIdForUser(id, principal.userId());
        if (job == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出作业不存在");
        }
        return job;
    }

    private void requireTask(long taskId) {
        if (taskId <= 0 || taskMapper.selectById(taskId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "超链任务不存在");
        }
    }

    private static void requirePrincipal(AuthPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.AUTH_INVALID);
        }
    }

    private HyperlinkTaskExportJobVO toVO(HyperlinkTaskExportJob job) {
        String status = effectiveStatus(job);
        return new HyperlinkTaskExportJobVO(
                job.getId(), job.getExportType(), status, job.getSnapshotAt(),
                job.getFileName(), job.getRowCount() == null ? 0 : job.getRowCount(),
                job.getErrorMessage(), job.getCreatedAt(), job.getFinishedAt(),
                STATUS_SUCCESS.equals(status));
    }

    private String effectiveStatus(HyperlinkTaskExportJob job) {
        if (STATUS_SUCCESS.equals(job.getStatus())
                && ((job.getExpiresAt() != null && job.getExpiresAt() <= clock.millis())
                    || job.getStorageKey() == null)) {
            return STATUS_EXPIRED;
        }
        return job.getStatus();
    }

    private static HyperlinkRecipientQuery toQuery(HyperlinkRecipientExportRequestDTO request) {
        HyperlinkRecipientQuery query = new HyperlinkRecipientQuery();
        if (request == null) {
            return query;
        }
        query.setPhone(request.phone());
        query.setRecipientCountryIso2(request.recipientCountryIso2());
        query.setSenderCountryIso2(request.senderCountryIso2());
        query.setFailReason(request.failReason());
        query.setSortField(request.sortField());
        query.setSortOrder(request.sortOrder());
        return query;
    }

    private void markFailed(HyperlinkTaskExportJob job, String claimToken, String message) {
        exportMapper.markRecipientJobFailed(job.getTenantId(), job.getId(), claimToken,
                truncate(message, 500), clock.millis());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "导出范围序列化失败");
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
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
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
            // 不覆盖作业的原始失败原因，残留 .part 文件由运维清理。
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
        String normalized = value == null || value.isBlank() ? "导出失败，请重新操作" : value.trim();
        return normalized.length() <= maxLength
                ? normalized : normalized.substring(0, maxLength);
    }
}
