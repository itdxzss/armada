package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientClaimMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipientClaim;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在 HTTP 短事务中只登记释放意图，实际大批量清理由恢复任务推进。 */
@Service
public class HyperlinkCleanupStartService {
    private static final int CLAIM_OWNED = 3;
    private final HyperlinkTaskRecipientClaimMapper claimMapper;
    private final HyperlinkTaskRoundMapper roundMapper;
    private final HyperlinkTaskRecipientMapper recipientMapper;
    private final HyperlinkBillingSagaService billingSagaService;

    public HyperlinkCleanupStartService(HyperlinkTaskRecipientClaimMapper claimMapper,
            HyperlinkTaskRoundMapper roundMapper, HyperlinkTaskRecipientMapper recipientMapper,
            HyperlinkBillingSagaService billingSagaService) {
        this.claimMapper = claimMapper;
        this.roundMapper = roundMapper;
        this.recipientMapper = recipientMapper;
        this.billingSagaService = billingSagaService;
    }

    public void requireNoCommandedRecipient(long taskId) {
        if (recipientMapper.countCommanded(taskId) > 0) {
            throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT,
                    "任务已产生发送命令，冻结范围不可编辑");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void begin(long taskId, boolean finalizeBilling, long now) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "超链清理缺少租户上下文");
        }
        HyperlinkTaskRecipientClaim claim = claimMapper.selectByTaskId(tenantId, taskId);
        if (claim != null) {
            if (Integer.valueOf(CLAIM_OWNED).equals(claim.getClaimStatus())) {
                billingSagaService.abandonFailedStaleUncalledReservation(taskId);
            } else {
                billingSagaService.abandonUnstartedReservation(taskId);
            }
        }
        if (finalizeBilling) {
            billingSagaService.beginFinalization(taskId);
        }
        claimMapper.markReleasing(taskId, now);
        roundMapper.cancelUnconsumed(taskId, now);
    }
}
