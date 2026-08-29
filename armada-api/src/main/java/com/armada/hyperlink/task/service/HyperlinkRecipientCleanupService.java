package com.armada.hyperlink.task.service;

import com.armada.hyperlink.data.service.DataPackageRecipientClaimService;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientClaimMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipientClaim;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** STOP/未开始编辑重建的 recipient 与号码池短事务清理批次。 */
@Service
public class HyperlinkRecipientCleanupService {
    private static final int BATCH_SIZE = 500;
    private final HyperlinkTaskRecipientClaimMapper claimMapper;
    private final HyperlinkTaskRecipientMapper recipientMapper;
    private final HyperlinkTaskRuntimeMapper runtimeMapper;
    private final DataPackageRecipientClaimService dataPackageService;
    private final Clock clock;

    public HyperlinkRecipientCleanupService(HyperlinkTaskRecipientClaimMapper claimMapper,
            HyperlinkTaskRecipientMapper recipientMapper, HyperlinkTaskRuntimeMapper runtimeMapper,
            DataPackageRecipientClaimService dataPackageService) {
        this(claimMapper, recipientMapper, runtimeMapper, dataPackageService, Clock.systemUTC());
    }

    HyperlinkRecipientCleanupService(HyperlinkTaskRecipientClaimMapper claimMapper,
            HyperlinkTaskRecipientMapper recipientMapper, HyperlinkTaskRuntimeMapper runtimeMapper,
            DataPackageRecipientClaimService dataPackageService, Clock clock) {
        this.claimMapper = claimMapper;
        this.recipientMapper = recipientMapper;
        this.runtimeMapper = runtimeMapper;
        this.dataPackageService = dataPackageService;
        this.clock = clock;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean cleanupBatch(long taskId) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "超链释放缺少租户上下文");
        }
        HyperlinkTaskRuntime runtime = runtimeMapper.selectByTaskIdForUpdate(tenantId, taskId);
        HyperlinkTaskRecipientClaim claim = claimMapper.selectByTaskId(tenantId, taskId);
        if (claim == null || claim.getClaimStatus() == 5) { return true; }
        boolean stop = runtime != null && runtime.getRunStatus() == 4;
        long now = clock.millis();
        List<HyperlinkTaskRecipient> stopRecipients = stop
                ? recipientMapper.lockUnsubmittedForStop(tenantId, taskId, claim.getDataPackageId(),
                        claim.getDataPackageGeneration(), BATCH_SIZE) : List.of();
        int recipientAffected = stop ? stopRecipients(stopRecipients, taskId, now)
                : recipientMapper.deleteUnsubmitted(taskId, BATCH_SIZE);
        int released = stop
                ? dataPackageService.releasePhones(taskId, claim.getDataPackageId(),
                        claim.getDataPackageGeneration(), stopRecipients.stream()
                                .map(HyperlinkTaskRecipient::getRecipientPhoneSnapshot).toList(), now)
                : dataPackageService.releaseOwnedBatch(taskId, claim.getDataPackageId(),
                        claim.getDataPackageGeneration(), BATCH_SIZE, now);
        if (stop && released != recipientAffected) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "STOP recipient 与号码池释放数量不一致");
        }
        if (recipientAffected == 0 && released == 0) {
            claimMapper.markReleased(taskId, now);
            return true;
        }
        return false;
    }

    private int stopRecipients(List<HyperlinkTaskRecipient> recipients, long taskId, long now) {
        if (recipients.isEmpty()) { return 0; }
        int affected = recipientMapper.stopUnsubmittedByIds(taskId,
                recipients.stream().map(HyperlinkTaskRecipient::getId).toList(), now);
        if (affected != recipients.size()) {
            throw new BusinessException(ErrorCode.CONFLICT, "STOP recipient 集合发生并发变化");
        }
        return affected;
    }
}
