package com.armada.hyperlink.task.service;

import com.armada.hyperlink.data.model.vo.DataPackageClaimCountryCount;
import com.armada.hyperlink.data.model.vo.DataPackageClaimSnapshot;
import com.armada.hyperlink.task.mapper.HyperlinkBillingReservationMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientClaimMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkBillingReservation;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipientClaim;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime;
import com.armada.hyperlink.task.model.enums.HyperlinkBillingOperation;
import com.armada.hyperlink.task.model.enums.HyperlinkBillingStatus;
import com.armada.hyperlink.task.model.enums.HyperlinkProvisionStatus;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskRunStatus;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 为领取人数变化且尚未调用钱包的失败任务，按本任务已持有 recipient 重新报价。 */
@Service
public class HyperlinkOwnedRecipientQuoteService {
    private static final int CLAIM_OWNED = 3;
    private final HyperlinkTaskRuntimeMapper runtimeMapper;
    private final HyperlinkTaskRecipientClaimMapper claimMapper;
    private final HyperlinkBillingReservationMapper billingMapper;
    private final HyperlinkTaskRecipientMapper recipientMapper;

    public HyperlinkOwnedRecipientQuoteService(HyperlinkTaskRuntimeMapper runtimeMapper,
            HyperlinkTaskRecipientClaimMapper claimMapper,
            HyperlinkBillingReservationMapper billingMapper,
            HyperlinkTaskRecipientMapper recipientMapper) {
        this.runtimeMapper = runtimeMapper;
        this.claimMapper = claimMapper;
        this.billingMapper = billingMapper;
        this.recipientMapper = recipientMapper;
    }

    /** 非目标失败返回空；目标失败的旧事实不完整时明确失败，禁止退回池快照静默换人数。 */
    public Optional<DataPackageClaimSnapshot> snapshot(HyperlinkTask task) {
        HyperlinkTaskRuntime runtime = runtimeMapper.selectByTaskId(task.getId());
        if (!isQuoteStaleFailure(runtime)) {
            return Optional.empty();
        }
        HyperlinkTaskRecipientClaim claim = claimMapper.selectSnapshotByTaskId(
                task.getTenantId(), task.getId());
        HyperlinkBillingReservation billing = billingMapper.selectByTaskId(task.getId());
        requireRecoverable(task, claim, billing);
        int recipientCount = recipientMapper.countByTaskId(task.getId());
        List<DataPackageClaimCountryCount> countryCounts = recipientMapper
                .selectCountryCounts(task.getId()).stream()
                .map(row -> new DataPackageClaimCountryCount(
                        row.countryIso2(), row.recipientCount()))
                .toList();
        int countryTotal = countryCounts.stream()
                .mapToInt(DataPackageClaimCountryCount::recipientCount).sum();
        if (recipientCount != claim.getClaimedPhoneCount() || countryTotal != recipientCount
                || recipientMapper.countCommanded(task.getId()) != 0) {
            throw stateConflict("旧 recipient 事实不完整，不能重新报价");
        }
        return Optional.of(new DataPackageClaimSnapshot(claim.getDataPackageId(),
                claim.getDataPackageGeneration(), task.getDataPackageNameSnapshot(),
                claim.getClaimUpperPhoneId(), recipientCount, countryCounts));
    }

    private boolean isQuoteStaleFailure(HyperlinkTaskRuntime runtime) {
        return runtime != null && Boolean.TRUE.equals(runtime.getEnabled())
                && Integer.valueOf(HyperlinkTaskRunStatus.NOT_STARTED.code())
                        .equals(runtime.getRunStatus())
                && Integer.valueOf(HyperlinkProvisionStatus.FAILED.code())
                        .equals(runtime.getProvisionStatus())
                && Integer.valueOf(ErrorCode.HYPERLINK_QUOTE_STALE.code())
                        .equals(runtime.getFailureCode());
    }

    private void requireRecoverable(HyperlinkTask task, HyperlinkTaskRecipientClaim claim,
            HyperlinkBillingReservation billing) {
        boolean claimMatches = claim != null && Integer.valueOf(CLAIM_OWNED)
                .equals(claim.getClaimStatus())
                && Objects.equals(task.getDataPackageId(), claim.getDataPackageId())
                && Objects.equals(task.getDataPackageGeneration(), claim.getDataPackageGeneration());
        boolean billingUncalled = billing != null
                && Integer.valueOf(HyperlinkBillingStatus.FAILED.code())
                        .equals(billing.getReservationStatus())
                && Integer.valueOf(HyperlinkBillingOperation.RESERVE.code())
                        .equals(billing.getPendingOperation())
                && Integer.toString(ErrorCode.HYPERLINK_QUOTE_STALE.code())
                        .equals(billing.getFailureCode())
                && billing.getExternalReservationNo() == null
                && billing.getReservedAmount() != null
                && BigDecimal.ZERO.compareTo(billing.getReservedAmount()) == 0
                && billing.getSettledAmount() != null
                && BigDecimal.ZERO.compareTo(billing.getSettledAmount()) == 0
                && billing.getSettledSendCount() != null
                && billing.getSettledSendCount() == 0;
        if (!claimMatches || !billingUncalled) {
            throw stateConflict("旧领取或计费事实不能安全重新报价");
        }
    }

    private BusinessException stateConflict(String message) {
        return new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT, message);
    }
}
