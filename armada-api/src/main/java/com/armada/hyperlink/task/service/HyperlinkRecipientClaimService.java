package com.armada.hyperlink.task.service;

import com.armada.hyperlink.data.model.vo.DataPackageClaimPhone;
import com.armada.hyperlink.data.service.DataPackageRecipientClaimService;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientClaimMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipientClaim;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** recipient 每批最多 50 行的可恢复、幂等领取事务。 */
@Service
public class HyperlinkRecipientClaimService {
    public static final int BATCH_SIZE = 50;
    private final HyperlinkTaskRecipientClaimMapper claimMapper;
    private final HyperlinkTaskRecipientMapper recipientMapper;
    private final DataPackageRecipientClaimService dataPackageService;
    private final Clock clock;

    @Autowired
    public HyperlinkRecipientClaimService(HyperlinkTaskRecipientClaimMapper claimMapper,
            HyperlinkTaskRecipientMapper recipientMapper,
            DataPackageRecipientClaimService dataPackageService) {
        this(claimMapper, recipientMapper, dataPackageService, Clock.systemUTC());
    }

    HyperlinkRecipientClaimService(HyperlinkTaskRecipientClaimMapper claimMapper,
            HyperlinkTaskRecipientMapper recipientMapper,
            DataPackageRecipientClaimService dataPackageService,
            Clock clock) {
        this.claimMapper = claimMapper;
        this.recipientMapper = recipientMapper;
        this.dataPackageService = dataPackageService;
        this.clock = clock;
    }

    @Transactional(rollbackFor = Exception.class)
    public ClaimBatchResult claimNext(long taskId) {
        HyperlinkTaskRecipientClaim claim = claimMapper.selectByTaskId(tenantId(), taskId);
        if (claim == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "recipient claim 不存在");
        }
        if (claim.getClaimStatus() == 3) {
            return new ClaimBatchResult(true, 0, claim.getClaimedPhoneCount());
        }
        long now = clock.millis();
        List<DataPackageClaimPhone> phones = dataPackageService.claimBatch(taskId,
                claim.getDataPackageId(), claim.getDataPackageGeneration(),
                claim.getScanCursorPhoneId(), claim.getClaimUpperPhoneId(), BATCH_SIZE, now);
        long cursor = phones.isEmpty() ? claim.getClaimUpperPhoneId()
                : phones.get(phones.size() - 1).id();
        boolean completed = phones.size() < BATCH_SIZE || cursor >= claim.getClaimUpperPhoneId();
        int affected = 0;
        if (!phones.isEmpty()) {
            affected = phones.size();
            List<HyperlinkTaskRecipient> recipients = phones.stream()
                    .map(phone -> recipient(taskId, claim, phone, now)).toList();
            recipientMapper.insertIgnoreBatch(recipients);
        }
        if (claimMapper.advance(claim.getId(), claim.getVersion(), cursor, affected, completed, now) != 1) {
            throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT, "recipient claim 已被并发推进");
        }
        return new ClaimBatchResult(completed, affected,
                claim.getClaimedPhoneCount() + affected);
    }

    private HyperlinkTaskRecipient recipient(long taskId, HyperlinkTaskRecipientClaim claim,
            DataPackageClaimPhone phone, long now) {
        HyperlinkTaskRecipient row = new HyperlinkTaskRecipient();
        row.setHyperlinkTaskId(taskId);
        row.setDataPackageId(claim.getDataPackageId());
        row.setDataPackageGeneration(claim.getDataPackageGeneration());
        row.setSourceImportId(phone.sourceImportId());
        row.setRecipientPhoneSnapshot(phone.phone());
        row.setRecipientCountryIso2Snapshot(phone.countryIso2());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private long tenantId() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "超链领取缺少租户上下文");
        }
        return tenantId;
    }

    public record ClaimBatchResult(boolean completed, int claimedThisBatch, int claimedTotal) { }
}
