package com.armada.hyperlink.data.service.impl;

import com.armada.hyperlink.data.mapper.DataPackageMapper;
import com.armada.hyperlink.data.mapper.DataPackagePhoneMapper;
import com.armada.hyperlink.data.mapper.DataPackageStatMapper;
import com.armada.hyperlink.data.model.entity.DataPackage;
import com.armada.hyperlink.data.model.entity.DataPackagePhone;
import com.armada.hyperlink.data.model.vo.DataPackageClaimPhone;
import com.armada.hyperlink.data.model.vo.DataPackageClaimSnapshot;
import com.armada.hyperlink.data.model.enums.DataPackagePoolStatus;
import com.armada.hyperlink.data.service.DataPackageRecipientClaimService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 数据包域内封装号码池锁、代次与统计维护，任务域不直接穿透 Mapper。 */
@Service
public class DataPackageRecipientClaimServiceImpl implements DataPackageRecipientClaimService {
    private final DataPackageMapper packageMapper;
    private final DataPackagePhoneMapper phoneMapper;
    private final DataPackageStatMapper statMapper;

    public DataPackageRecipientClaimServiceImpl(DataPackageMapper packageMapper,
            DataPackagePhoneMapper phoneMapper, DataPackageStatMapper statMapper) {
        this.packageMapper = packageMapper;
        this.phoneMapper = phoneMapper;
        this.statMapper = statMapper;
    }

    @Override
    public DataPackageClaimSnapshot snapshot(long dataPackageId) {
        DataPackage dataPackage = packageMapper.selectActiveById(dataPackageId);
        if (dataPackage == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "数据包不存在");
        }
        int generation = dataPackage.getCurrentGeneration();
        long upperId = phoneMapper.selectClaimUpperPhoneId(dataPackageId, generation);
        int count = phoneMapper.countClaimable(dataPackageId, generation, upperId);
        return new DataPackageClaimSnapshot(dataPackageId, generation, dataPackage.getPackageName(),
                upperId, count, phoneMapper.selectClaimableCountryCounts(
                        dataPackageId, generation, upperId));
    }

    @Override
    public boolean isCurrentGeneration(long dataPackageId, int generation) {
        DataPackage value = packageMapper.selectActiveById(dataPackageId);
        return value != null && value.getCurrentGeneration() == generation;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<DataPackageClaimPhone> claimBatch(long taskId, long dataPackageId, int generation,
            long cursor, long upperId, int limit, long now) {
        statMapper.selectForUpdate(dataPackageId, generation);
        List<DataPackagePhone> phones = phoneMapper.lockNextClaimable(
                tenantId(), dataPackageId, generation, cursor, upperId, limit);
        if (phones.isEmpty()) { return List.of(); }
        int affected = phoneMapper.claimByIds(phones.stream().map(DataPackagePhone::getId).toList(),
                taskId, now);
        if (affected != phones.size()
                || statMapper.moveUnusedToClaimed(dataPackageId, generation, affected, now) != 1) {
            throw new BusinessException(ErrorCode.HYPERLINK_QUOTE_STALE,
                    "数据包领取快照发生并发变化");
        }
        return phones.stream().map(phone -> new DataPackageClaimPhone(phone.getId(),
                phone.getSourceImportId(), phone.getPhone(), phone.getCountryIso2())).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int releasePhones(long taskId, long dataPackageId, int generation,
            List<String> phones, long now) {
        if (phones == null || phones.isEmpty()) { return 0; }
        statMapper.selectForUpdate(dataPackageId, generation);
        int affected = phoneMapper.releaseByPhones(phones, taskId, dataPackageId, generation, now);
        if (affected > 0 && statMapper.moveClaimedToUnused(
                dataPackageId, generation, affected, now) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据包释放统计发生变化");
        }
        return affected;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int releaseOwnedBatch(long taskId, long dataPackageId, int generation,
            int limit, long now) {
        statMapper.selectForUpdate(dataPackageId, generation);
        List<DataPackagePhone> phones = phoneMapper.lockOwnedBatch(
                tenantId(), taskId, dataPackageId, generation, limit);
        if (phones.isEmpty()) { return 0; }
        int affected = phoneMapper.releaseByIds(
                phones.stream().map(DataPackagePhone::getId).toList(), taskId, now);
        if (affected != phones.size() || statMapper.moveClaimedToUnused(
                dataPackageId, generation, affected, now) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据包释放批次发生变化");
        }
        return affected;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void advanceDeliveryFact(long taskId, long dataPackageId, int generation, String phone,
            DataPackagePoolStatus targetStatus, long now) {
        if (targetStatus != DataPackagePoolStatus.SENT
                && targetStatus != DataPackagePoolStatus.DELIVERED
                && targetStatus != DataPackagePoolStatus.RETRYABLE_FAILED
                && targetStatus != DataPackagePoolStatus.UNREGISTERED) {
            throw new BusinessException(ErrorCode.VALIDATION, "号码池发送结果状态非法");
        }
        boolean currentGeneration = statMapper.selectForUpdate(dataPackageId, generation) != null;
        DataPackagePhone phoneFact = phoneMapper.selectOwnedPhoneForUpdate(
                taskId, dataPackageId, generation, phone);
        if (phoneFact == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务号码池归属事实不存在");
        }
        DataPackagePoolStatus current = DataPackagePoolStatus.fromCode(phoneFact.getPoolStatus());
        DataPackagePoolStatus next = monotonicPoolStatus(current, targetStatus);
        if (next == current) { return; }
        if (phoneMapper.advanceOwnedStatus(phoneFact.getId(), current.code(), next.code(), now) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务号码池发送事实发生并发变化");
        }
        if (currentGeneration && statMapper.moveDeliveryStatus(dataPackageId, generation,
                current.code(), next.code(), DataPackagePoolStatus.CLAIMED.code(),
                DataPackagePoolStatus.SENT.code(), DataPackagePoolStatus.DELIVERED.code(),
                DataPackagePoolStatus.RETRYABLE_FAILED.code(),
                DataPackagePoolStatus.UNREGISTERED.code(), now) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务号码池发送统计发生变化");
        }
    }

    private DataPackagePoolStatus monotonicPoolStatus(
            DataPackagePoolStatus current, DataPackagePoolStatus incoming) {
        if (current == DataPackagePoolStatus.DELIVERED
                || current == DataPackagePoolStatus.UNREGISTERED) {
            return current;
        }
        if (incoming == DataPackagePoolStatus.DELIVERED) {
            return incoming;
        }
        return current == DataPackagePoolStatus.CLAIMED ? incoming : current;
    }

    private long tenantId() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "数据包领取缺少租户上下文");
        }
        return tenantId;
    }
}
