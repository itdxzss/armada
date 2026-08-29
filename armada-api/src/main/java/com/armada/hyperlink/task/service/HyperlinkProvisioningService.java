package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.shared.exception.BusinessException;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 分批 claim、任务级预约与首轮事务的可恢复编排。 */
@Service
public class HyperlinkProvisioningService {
    private final HyperlinkRecipientClaimService claimService;
    private final HyperlinkBillingSagaService billingSagaService;
    private final HyperlinkFirstRoundService firstRoundService;
    private final HyperlinkTaskRuntimeMapper runtimeMapper;
    private final Clock clock;

    @Autowired
    public HyperlinkProvisioningService(HyperlinkRecipientClaimService claimService,
            HyperlinkBillingSagaService billingSagaService,
            HyperlinkFirstRoundService firstRoundService,
            HyperlinkTaskRuntimeMapper runtimeMapper) {
        this(claimService, billingSagaService, firstRoundService, runtimeMapper,
                Clock.systemUTC());
    }

    HyperlinkProvisioningService(HyperlinkRecipientClaimService claimService,
            HyperlinkBillingSagaService billingSagaService,
            HyperlinkFirstRoundService firstRoundService,
            HyperlinkTaskRuntimeMapper runtimeMapper, Clock clock) {
        this.claimService = claimService;
        this.billingSagaService = billingSagaService;
        this.firstRoundService = firstRoundService;
        this.runtimeMapper = runtimeMapper;
        this.clock = clock;
    }

    /** 每次只领取一批，给所有任务公平恢复机会。 */
    public void advance(long taskId) {
        try {
            HyperlinkRecipientClaimService.ClaimBatchResult result = claimService.claimNext(taskId);
            if (!result.completed()) { return; }
            billingSagaService.ensureProvisionReservation(taskId);
            firstRoundService.createFirstRound(taskId);
        } catch (BusinessException exception) {
            long now = clock.millis();
            runtimeMapper.markProvisionFailed(taskId, exception.getCode(),
                    safeReason(exception.getMessage()), now);
        }
    }

    private String safeReason(String reason) {
        if (reason == null) { return "准备失败"; }
        return reason.length() <= 255 ? reason : reason.substring(0, 255);
    }
}
