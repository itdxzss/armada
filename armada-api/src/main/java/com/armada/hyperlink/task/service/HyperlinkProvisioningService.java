package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.shared.exception.BusinessException;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 分批 claim、任务级预约与首轮事务的可恢复编排。 */
@Service
public class HyperlinkProvisioningService {
    static final int MAX_CLAIM_BATCHES_PER_ADVANCE = 4;
    static final long CLAIM_TIME_BUDGET_MILLIS = 1_000L;

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

    /**
     * 单次推进连续执行有限个短 claim 事务；批次数和耗时双重限流，避免大事务并给其他任务恢复机会。
     */
    public void advance(long taskId) {
        try {
            long deadlineAt = clock.millis() + CLAIM_TIME_BUDGET_MILLIS;
            int claimedBatches = 0;
            HyperlinkRecipientClaimService.ClaimBatchResult result;
            do {
                result = claimService.claimNext(taskId);
                claimedBatches++;
            } while (!result.completed()
                    && claimedBatches < MAX_CLAIM_BATCHES_PER_ADVANCE
                    && clock.millis() < deadlineAt);
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
