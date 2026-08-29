package com.armada.hyperlink.data.service.impl;

import com.armada.hyperlink.data.mapper.DataPackageImportMapper;
import com.armada.hyperlink.data.mapper.DataPackageMapper;
import com.armada.hyperlink.data.mapper.DataPackagePhoneMapper;
import com.armada.hyperlink.data.mapper.DataPackageStatMapper;
import com.armada.hyperlink.data.model.entity.DataPackage;
import com.armada.hyperlink.data.model.entity.DataPackageStat;
import com.armada.hyperlink.data.model.enums.DataPackageImportMode;
import com.armada.hyperlink.data.model.enums.DataPackageImportStatus;
import com.armada.hyperlink.data.model.enums.DataPackagePoolStatus;
import com.armada.hyperlink.data.model.vo.DataPackagePhoneCleanupRow;
import com.armada.hyperlink.data.model.vo.DataPackageStatusCountRow;
import com.armada.hyperlink.data.service.DataPackageMaintenanceService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 数据包内部校准、30 天号码清理与 PROCESSING 超时恢复实现。 */
@Service
public class DataPackageMaintenanceServiceImpl implements DataPackageMaintenanceService {

    private static final int MAX_CLEANUP_BATCH_SIZE = 2_000;
    private static final String TIMEOUT_REASON = "导入处理超时，已自动标记失败";

    private final DataPackageMapper packageMapper;
    private final DataPackagePhoneMapper phoneMapper;
    private final DataPackageStatMapper statMapper;
    private final DataPackageImportMapper importMapper;
    private final long retentionMillis;
    private final long processingTimeoutMillis;
    private final int cleanupBatchSize;
    private final int recoveryBatchSize;

    public DataPackageMaintenanceServiceImpl(
            DataPackageMapper packageMapper,
            DataPackagePhoneMapper phoneMapper,
            DataPackageStatMapper statMapper,
            DataPackageImportMapper importMapper,
            @Value("${armada.hyperlink.data-package.maintenance.retention-days:30}") int retentionDays,
            @Value("${armada.hyperlink.data-package.maintenance.cleanup-batch-size:2000}") int cleanupBatchSize,
            @Value("${armada.hyperlink.data-package.maintenance.processing-timeout-ms:1800000}")
                    long processingTimeoutMillis,
            @Value("${armada.hyperlink.data-package.maintenance.recovery-batch-size:2000}")
                    int recoveryBatchSize) {
        this.packageMapper = packageMapper;
        this.phoneMapper = phoneMapper;
        this.statMapper = statMapper;
        this.importMapper = importMapper;
        this.retentionMillis = Duration.ofDays(Math.max(1, retentionDays)).toMillis();
        this.cleanupBatchSize = Math.min(
                MAX_CLEANUP_BATCH_SIZE, Math.max(1, cleanupBatchSize));
        this.processingTimeoutMillis = Math.max(60_000L, processingTimeoutMillis);
        this.recoveryBatchSize = Math.max(1, recoveryBatchSize);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reconcile(Long dataPackageId) {
        DataPackage locked = packageMapper.selectActiveForUpdate(dataPackageId);
        if (locked == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "数据包不存在或已删除");
        }
        Map<DataPackagePoolStatus, Integer> counts = new EnumMap<>(DataPackagePoolStatus.class);
        for (DataPackageStatusCountRow row : phoneMapper.selectStatusCounts(
                locked.getId(), locked.getCurrentGeneration())) {
            counts.put(DataPackagePoolStatus.fromCode(row.getPoolStatus()), row.getRowCount());
        }
        long now = System.currentTimeMillis();
        DataPackageStat stat = reconciledStat(locked, counts, now);
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (statMapper.replaceCounts(stat) != 1
                || packageMapper.setPhoneCount(
                        locked.getId(), locked.getCurrentGeneration(), total, now) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据包统计代次已变化，请重试");
        }
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int purgeExpiredPhoneBatch() {
        long cutoff = System.currentTimeMillis() - retentionMillis;
        List<DataPackagePhoneCleanupRow> rows = phoneMapper.selectCleanupCandidates(
                cutoff,
                cutoff,
                DataPackageImportMode.OVERWRITE.code(),
                DataPackageImportStatus.SUCCESS.code(),
                cleanupBatchSize);
        return rows.isEmpty() ? 0 : phoneMapper.deleteCleanupCandidates(rows);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int recoverStaleImportBatch() {
        long now = System.currentTimeMillis();
        return importMapper.markStaleProcessingFailed(
                DataPackageImportStatus.PROCESSING.code(),
                DataPackageImportStatus.FAILED.code(),
                now - processingTimeoutMillis,
                TIMEOUT_REASON,
                now,
                recoveryBatchSize);
    }

    private static DataPackageStat reconciledStat(
            DataPackage dataPackage,
            Map<DataPackagePoolStatus, Integer> counts,
            long now) {
        DataPackageStat stat = new DataPackageStat();
        stat.setDataPackageId(dataPackage.getId());
        stat.setGeneration(dataPackage.getCurrentGeneration());
        stat.setUnusedCount(counts.getOrDefault(DataPackagePoolStatus.UNUSED, 0));
        stat.setClaimedCount(counts.getOrDefault(DataPackagePoolStatus.CLAIMED, 0));
        stat.setSentCount(counts.getOrDefault(DataPackagePoolStatus.SENT, 0));
        stat.setDeliveredCount(counts.getOrDefault(DataPackagePoolStatus.DELIVERED, 0));
        stat.setRetryableFailedCount(
                counts.getOrDefault(DataPackagePoolStatus.RETRYABLE_FAILED, 0));
        stat.setUnregisteredCount(
                counts.getOrDefault(DataPackagePoolStatus.UNREGISTERED, 0));
        stat.setUpdatedAt(now);
        stat.setReconciledAt(now);
        return stat;
    }
}
