package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkBillingReservationMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkBillingReservation;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRound;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskMode;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskRoundStatus;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 预约成功后按任务模式建立首轮并冻结可用账号集合。 */
@Service
public class HyperlinkFirstRoundService {
    private final HyperlinkTaskMapper taskMapper;
    private final HyperlinkTaskRuntimeMapper runtimeMapper;
    private final HyperlinkTaskRecipientMapper recipientMapper;
    private final HyperlinkBillingReservationMapper billingMapper;
    private final HyperlinkTaskRoundMapper roundMapper;
    private final HyperlinkRoundAccountSelectionService accountSelectionService;
    private final Clock clock;

    @Autowired
    public HyperlinkFirstRoundService(HyperlinkTaskMapper taskMapper,
            HyperlinkTaskRuntimeMapper runtimeMapper, HyperlinkTaskRecipientMapper recipientMapper,
            HyperlinkBillingReservationMapper billingMapper, HyperlinkTaskRoundMapper roundMapper,
            HyperlinkRoundAccountSelectionService accountSelectionService) {
        this(taskMapper, runtimeMapper, recipientMapper, billingMapper, roundMapper,
                accountSelectionService, Clock.systemUTC());
    }

    HyperlinkFirstRoundService(HyperlinkTaskMapper taskMapper,
            HyperlinkTaskRuntimeMapper runtimeMapper, HyperlinkTaskRecipientMapper recipientMapper,
            HyperlinkBillingReservationMapper billingMapper, HyperlinkTaskRoundMapper roundMapper,
            HyperlinkRoundAccountSelectionService accountSelectionService, Clock clock) {
        this.taskMapper = taskMapper;
        this.runtimeMapper = runtimeMapper;
        this.recipientMapper = recipientMapper;
        this.billingMapper = billingMapper;
        this.roundMapper = roundMapper;
        this.accountSelectionService = accountSelectionService;
        this.clock = clock;
    }

    @Transactional(rollbackFor = Exception.class)
    public void createFirstRound(long taskId) {
        if (roundMapper.selectActive(taskId) != null) { return; }
        HyperlinkTask task = taskMapper.selectById(taskId);
        HyperlinkBillingReservation billing = billingMapper.selectByTaskId(taskId);
        if (task == null || billing == null || billing.getReservationStatus() != 2) {
            throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT,
                    "首轮前置状态未收敛");
        }
        long now = clock.millis();
        long scheduledAt = task.getStartMode() == 2
                ? now + task.getTaskDelayMinutes() * 60_000L : now;
        HyperlinkTaskRound round = new HyperlinkTaskRound();
        round.setHyperlinkTaskId(taskId);
        round.setRoundNo(1L);
        round.setRoundStatus(HyperlinkTaskRoundStatus.SELECTING.code());
        round.setScheduledAt(scheduledAt);
        round.setNextDispatchAt(scheduledAt);
        round.setAssignedRecipientCount(0);
        round.setSelectedAccountCount(0);
        round.setActualConcurrency(0);
        round.setVersion(1);
        round.setCreatedAt(now);
        round.setUpdatedAt(now);
        roundMapper.insert(round);

        int selectedAccountCount = accountSelectionService.select(task, round, now);
        if (task.getTaskType() == HyperlinkTaskMode.INSTANT.code()
                && selectedAccountCount == 0) {
            throw new BusinessException(ErrorCode.HYPERLINK_ACCOUNT_UNAVAILABLE,
                    "即时任务启用前至少需要一个已验证 PRIVATE 能力的账号");
        }
        int status = selectedAccountCount == 0
                ? HyperlinkTaskRoundStatus.NO_ACCOUNT.code()
                : HyperlinkTaskRoundStatus.READY.code();
        roundMapper.markSelected(round.getId(), selectedAccountCount,
                selectedAccountCount, status, now);
        int recipients = recipientMapper.countByTaskId(taskId);
        if (runtimeMapper.markReady(taskId, recipients, round.getId(), now) != 1) {
            throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT,
                    "准备状态已被并发修改");
        }
    }

}
