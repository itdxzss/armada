package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskAnalysisExportMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkAttributionQuery;
import com.armada.hyperlink.task.model.dto.HyperlinkVisitTrendQuery;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskExportJobVO;
import com.armada.hyperlink.task.model.vo.HyperlinkVisitTrendVO;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
import com.armada.marketing.export.model.entity.MarketingTaskExportJob;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** H6 复用现有导出作业表、租约和文件目录的异步 CSV writer。 */
@Service
public class HyperlinkTaskAnalysisExportService {
    private static final int BATCH_SIZE = 1_000;
    private static final int MAX_ROWS = 100_000;
    private static final long LEASE_MS = Duration.ofMinutes(10).toMillis();
    private static final long RETENTION_MS = Duration.ofHours(24).toMillis();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Shanghai"));

    private final HyperlinkTaskAnalysisExportMapper exportMapper;
    private final HyperlinkTaskMapper taskMapper;
    private final HyperlinkTaskRecipientMapper recipientMapper;
    private final HyperlinkTaskAnalysisService analysisService;
    private final ObjectMapper objectMapper;
    private final Path storageRoot;
    private final HyperlinkTaskAuditPort auditPort;

    public HyperlinkTaskAnalysisExportService(HyperlinkTaskAnalysisExportMapper exportMapper,
            HyperlinkTaskMapper taskMapper, HyperlinkTaskRecipientMapper recipientMapper,
            HyperlinkTaskAnalysisService analysisService, ObjectMapper objectMapper,
            HyperlinkTaskAuditPort auditPort,
            @Value("${armada.marketing.export.storage-dir:/app/data/marketing-exports}")
            String storageDir) {
        this.exportMapper = exportMapper;
        this.taskMapper = taskMapper;
        this.recipientMapper = recipientMapper;
        this.analysisService = analysisService;
        this.objectMapper = objectMapper;
        this.auditPort = auditPort;
        this.storageRoot = Path.of(storageDir).toAbsolutePath().normalize();
    }

    @Transactional
    public HyperlinkTaskExportJobVO createAttribution(long taskId,
            HyperlinkAttributionQuery query, AuthPrincipal principal) {
        return create("ATTRIBUTION", taskId, new StoredAttributionPayload(
                query.getRecipientPhone(), query.getSenderPhone(), "visitCount",
                query.getSortOrder()), principal);
    }

    @Transactional
    public HyperlinkTaskExportJobVO createVisitTrend(long taskId,
            HyperlinkVisitTrendQuery query, AuthPrincipal principal) {
        HyperlinkVisitTrendQuery normalized = new HyperlinkVisitTrendQuery();
        normalized.setRange(query.getRange());
        normalized.setGranularity(query.getGranularity());
        HyperlinkVisitTrendVO frozen = analysisService.visitTrend(taskId, normalized);
        return create("VISIT_TREND", taskId, new StoredVisitTrendPayload(normalized, frozen),
                principal);
    }

    public int processNextBatch(int limit) {
        long now = System.currentTimeMillis();
        exportMapper.markExhausted(now, "导出重试次数已用尽，请重新发起");
        int processed = 0;
        for (MarketingTaskExportJob job : exportMapper.selectCandidates(now,
                Math.max(1, Math.min(limit, 5)))) {
            String token = UUID.randomUUID().toString();
            if (exportMapper.claim(job.getTenantId(), job.getId(), token, now + LEASE_MS, now) != 1) {
                continue;
            }
            process(job, token);
            processed++;
        }
        return processed;
    }

    private HyperlinkTaskExportJobVO create(String type, long taskId, Object request,
            AuthPrincipal principal) {
        if (principal == null || taskMapper.selectById(taskId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "超链任务不存在");
        }
        long now = System.currentTimeMillis();
        String requestJson = json(request);
        String hash = sha256(type + '|' + taskId + '|' + requestJson);
        MarketingTaskExportJob existing = exportMapper.selectActive(principal.userId(), hash);
        if (existing != null) return toVO(existing);

        auditPort.requireAvailable();

        MarketingTaskExportJob job = new MarketingTaskExportJob();
        job.setTenantId(principal.tenantId());
        job.setCreatedBy(principal.userId());
        job.setExportMode(type);
        job.setTaskIdsJson(json(List.of(taskId)));
        job.setCountryIso2sJson(requestJson);
        job.setRequestHash(hash);
        job.setStatus("PENDING");
        job.setSnapshotAt(now);
        job.setAttemptCount(0);
        job.setSummaryRowCount(0);
        job.setDetailRowCount(0);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        try {
            exportMapper.insert(job);
        } catch (DuplicateKeyException conflict) {
            existing = exportMapper.selectActive(principal.userId(), hash);
            if (existing != null) return toVO(existing);
            throw new BusinessException(ErrorCode.CONFLICT, "当前已有导出作业正在处理");
        }
        HyperlinkTaskAuditPort.Action action = "ATTRIBUTION".equals(type)
                ? HyperlinkTaskAuditPort.Action.ATTRIBUTION_EXPORT
                : HyperlinkTaskAuditPort.Action.VISIT_TREND_EXPORT;
        auditPort.record(new HyperlinkTaskAuditPort.AuditEvent(
                "hl-export-create:" + job.getId(), action, principal.tenantId(),
                principal.userId(), taskId, now));
        return toVO(job);
    }

    private void process(MarketingTaskExportJob job, String token) {
        Long previousTenant = TenantContext.get();
        Path temporary = null;
        try {
            TenantContext.set(job.getTenantId());
            long taskId = objectMapper.readValue(job.getTaskIdsJson(),
                    new TypeReference<List<Long>>() { }).get(0);
            Files.createDirectories(storageRoot.resolve("hyperlink"));
            temporary = Files.createTempFile(storageRoot.resolve("hyperlink"),
                    "job-" + job.getId() + '-', ".part");
            WriteResult result = "ATTRIBUTION".equals(job.getExportMode())
                    ? writeAttribution(temporary, taskId, job)
                    : writeTrend(temporary, taskId, job);
            String storageKey = "hyperlink/" + job.getTenantId() + '-' + job.getId() + ".csv";
            Path target = safeStoragePath(storageKey);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            long finishedAt = System.currentTimeMillis();
            exportMapper.markSuccess(job.getTenantId(), job.getId(), token, storageKey,
                    result.fileName(), Files.size(target), result.rowCount(), finishedAt,
                    finishedAt + RETENTION_MS);
        } catch (Exception exception) {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
            String message = exception instanceof BusinessException
                    ? exception.getMessage() : "导出文件生成失败，请稍后重试";
            exportMapper.markFailed(job.getTenantId(), job.getId(), token,
                    truncate(message, 500), System.currentTimeMillis());
        } finally {
            if (previousTenant == null) TenantContext.clear();
            else TenantContext.set(previousTenant);
        }
    }

    private WriteResult writeAttribution(Path file, long taskId, MarketingTaskExportJob job)
            throws IOException {
        StoredAttributionPayload payload = read(job.getCountryIso2sJson(),
                StoredAttributionPayload.class);
        HyperlinkAttributionQuery query = new HyperlinkAttributionQuery();
        query.setRecipientPhone(payload.recipientPhone());
        query.setSenderPhone(payload.senderPhone());
        query.setSortOrder(payload.sortOrder());
        int rowCount = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write('\uFEFF');
            writer.write("收件人手机号,发送账号,访问次数,国家/地区,设备,操作系统,浏览器,语言,IP,User-Agent,首次访问,最近访问,归因是否已清理\r\n");
            for (int offset = 0; ; offset += BATCH_SIZE) {
                List<HyperlinkTaskRecipient> rows = recipientMapper.selectClickedExportBatch(taskId,
                        query.getRecipientPhone(), query.getSenderPhone(), query.getSortOrder(),
                        job.getSnapshotAt(), offset, BATCH_SIZE);
                for (HyperlinkTaskRecipient row : rows) {
                    writeCsvRow(writer, List.of(csvValue(row.getRecipientPhoneSnapshot()),
                            csvValue(row.getSenderPhoneSnapshot()), csvValue(row.getClickCount()),
                            csvValue(row.getFirstVisitCountryIso2()), csvValue(row.getFirstVisitDevice()),
                            csvValue(row.getFirstVisitOs()), csvValue(row.getFirstVisitBrowser()),
                            csvValue(row.getFirstVisitLanguage()), csvValue(ip(row.getFirstVisitIpAddress())),
                            csvValue(row.getFirstVisitUserAgent()), csvValue(row.getFirstVisitAt()),
                            csvValue(row.getLastVisitAt()), csvValue(row.getAttributionPurgedAt() != null ? "是" : "否")));
                    if (++rowCount > MAX_ROWS) {
                        throw new BusinessException(ErrorCode.VALIDATION, "单次导出最多 100000 行");
                    }
                }
                if (rows.size() < BATCH_SIZE) break;
            }
        }
        return new WriteResult("hyperlink-click-attribution-" + taskId + '-'
                + FILE_TIME.format(Instant.ofEpochMilli(job.getSnapshotAt())) + ".csv", rowCount);
    }

    private WriteResult writeTrend(Path file, long taskId, MarketingTaskExportJob job)
            throws IOException {
        StoredVisitTrendPayload payload = read(job.getCountryIso2sJson(),
                StoredVisitTrendPayload.class);
        var trend = payload.trend();
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write('\uFEFF');
            writer.write("时间段开始,时间段结束,新增UV,累计UV,累计点击率,PV（无逐次事实，分桶不可用）\r\n");
            for (var row : trend.series()) {
                writeCsvRow(writer, List.of(csvValue(row.bucketTime()), csvValue(row.bucketEndTime()),
                        csvValue(row.newUv()), csvValue(row.cumulativeUv()),
                        csvValue(row.cumulativeClickRate()), ""));
            }
        }
        return new WriteResult("hyperlink-visit-trend-" + taskId + '-'
                + FILE_TIME.format(Instant.ofEpochMilli(job.getSnapshotAt())) + ".csv",
                trend.series().size());
    }

    private void writeCsvRow(BufferedWriter writer, List<String> values) throws IOException {
        writer.write(String.join(",", values));
        writer.write("\r\n");
    }

    private String csvValue(Object value) {
        String text = value == null ? "" : value.toString();
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private String ip(byte[] bytes) {
        if (bytes == null) return null;
        try { return InetAddress.getByAddress(bytes).getHostAddress(); }
        catch (UnknownHostException ignored) { return null; }
    }

    private Path safeStoragePath(String key) throws IOException {
        Path target = storageRoot.resolve(key).normalize();
        if (!target.startsWith(storageRoot)) throw new IOException("非法导出存储路径");
        Files.createDirectories(target.getParent());
        return target;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new BusinessException(ErrorCode.VALIDATION, "导出请求无法序列化"); }
    }

    private <T> T read(String json, Class<T> type) throws IOException {
        return objectMapper.readValue(json, type);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String truncate(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(max, value.length()));
    }

    private HyperlinkTaskExportJobVO toVO(MarketingTaskExportJob job) {
        return new HyperlinkTaskExportJobVO(job.getId(), job.getExportMode(), job.getStatus(),
                job.getSnapshotAt(), job.getFileName(), value(job.getDetailRowCount()),
                job.getErrorMessage(), job.getCreatedAt(), job.getFinishedAt(),
                "SUCCESS".equals(job.getStatus()) && job.getStorageKey() != null);
    }

    private int value(Integer value) { return value == null ? 0 : value; }
    private record WriteResult(String fileName, int rowCount) { }
    private record StoredAttributionPayload(String recipientPhone, String senderPhone,
            String sortField, String sortOrder) { }
    private record StoredVisitTrendPayload(HyperlinkVisitTrendQuery query,
            HyperlinkVisitTrendVO trend) { }
}
