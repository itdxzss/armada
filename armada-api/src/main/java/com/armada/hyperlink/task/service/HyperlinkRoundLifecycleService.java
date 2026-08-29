package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundAccountMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRound;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskMode;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskRoundStatus;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskRunStatus;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 三种任务模式共用的 round 推进与零账号收口。 */
@Service
public class HyperlinkRoundLifecycleService {
    private static final long ACCOUNT_RECHECK_DELAY_MS = 30_000L;

    private final HyperlinkTaskMapper taskMapper;
    private final HyperlinkTaskRuntimeMapper runtimeMapper;
    private final HyperlinkTaskRoundMapper roundMapper;
    private final HyperlinkTaskRoundAccountMapper roundAccountMapper;
    private final HyperlinkTaskRecipientMapper recipientMapper;
    private final HyperlinkTaskAccountUsageMapper usageMapper;
    private final HyperlinkRoundAccountSelectionService selectionService;
    private final HyperlinkCleanupStartService cleanupStartService;
    private final Clock clock;

    @Autowired
    public HyperlinkRoundLifecycleService(HyperlinkTaskMapper taskMapper,
            HyperlinkTaskRuntimeMapper runtimeMapper, HyperlinkTaskRoundMapper roundMapper,
            HyperlinkTaskRoundAccountMapper roundAccountMapper,
            HyperlinkTaskRecipientMapper recipientMapper,
            HyperlinkTaskAccountUsageMapper usageMapper,
            HyperlinkRoundAccountSelectionService selectionService,
            HyperlinkCleanupStartService cleanupStartService) {
        this(taskMapper, runtimeMapper, roundMapper, roundAccountMapper, recipientMapper,
                usageMapper, selectionService, cleanupStartService, Clock.systemUTC());
    }

    HyperlinkRoundLifecycleService(HyperlinkTaskMapper taskMapper,
            HyperlinkTaskRuntimeMapper runtimeMapper, HyperlinkTaskRoundMapper roundMapper,
            HyperlinkTaskRoundAccountMapper roundAccountMapper,
            HyperlinkTaskRecipientMapper recipientMapper,
            HyperlinkTaskAccountUsageMapper usageMapper,
            HyperlinkRoundAccountSelectionService selectionService,
            HyperlinkCleanupStartService cleanupStartService, Clock clock) {
        this.taskMapper = taskMapper;
        this.runtimeMapper = runtimeMapper;
        this.roundMapper = roundMapper;
        this.roundAccountMapper = roundAccountMapper;
        this.recipientMapper = recipientMapper;
        this.usageMapper = usageMapper;
        this.selectionService = selectionService;
        this.cleanupStartService = cleanupStartService;
        this.clock = clock;
    }

    /** 到期首轮/周期轮按 runtime→round 固定顺序启动。 */
    @Transactional(rollbackFor = Exception.class)
    public void startDue(long taskId) {
        long now = clock.millis();
        HyperlinkTaskRuntime runtime = lockRuntime(taskId);
        startDueLocked(taskId, runtime, now);
    }

    private void startDueLocked(long taskId, HyperlinkTaskRuntime runtime, long now) {
        HyperlinkTaskRound round = roundMapper.selectActive(taskId);
        if (round == null || runtime == null || round.getScheduledAt() > now
                || (round.getRoundStatus() != HyperlinkTaskRoundStatus.READY.code()
                && round.getRoundStatus() != HyperlinkTaskRoundStatus.NO_ACCOUNT.code())) {
            return;
        }
        if (roundMapper.markStarted(round.getId(), now) != 1) {
            return;
        }
        if (runtime.getRunStatus() == HyperlinkTaskRunStatus.NOT_STARTED.code()) {
            runtimeMapper.startRound(taskId, round.getId(), round.getRoundNo(),
                    round.getActualConcurrency(), now);
        } else if (runtime.getRunStatus() == HyperlinkTaskRunStatus.RUNNING.code()) {
            runtimeMapper.updateCurrentRound(taskId, round.getId(), round.getRoundNo(),
                    round.getActualConcurrency(), now);
        }
    }

    /** 推进当前 round；自然完成后由独立 completion service 结算任务。 */
    @Transactional(rollbackFor = Exception.class)
    public void advance(long taskId) {
        long now = clock.millis();
        HyperlinkTaskRuntime runtime = lockRuntime(taskId);
        HyperlinkTask task = taskMapper.selectById(taskId);
        HyperlinkTaskRound round = roundMapper.selectActive(taskId);
        if (task == null || runtime == null || round == null
                || runtime.getRunStatus() != HyperlinkTaskRunStatus.RUNNING.code()) {
            return;
        }
        HyperlinkTaskMode mode = mode(task.getTaskType());
        if (mode == HyperlinkTaskMode.ROLLING && task.getTaskPlannedEndAt() != null
                && task.getTaskPlannedEndAt() <= now) {
            if (runtimeMapper.stopAtDeadline(taskId, now) == 1) {
                cleanupStartService.begin(taskId, true, now);
            }
            return;
        }
        if (round.getRoundStatus() == HyperlinkTaskRoundStatus.PLANNED.code()) {
            if (round.getScheduledAt() > now) {
                return;
            }
            selectPlanned(task, round, now);
            startDueLocked(taskId, runtime, now);
            round = roundMapper.selectActive(taskId);
        }
        roundAccountMapper.syncUnavailableFromUsage(round.getId(), now);
        int pending = recipientMapper.countPendingUnassigned(taskId);
        int sending = recipientMapper.countSendingByRoundId(round.getId());
        int available = roundAccountMapper.countAvailableByRoundId(round.getId());
        if (pending == 0) {
            settleRound(round, sending, now);
            return;
        }
        if (available > 0) {
            return;
        }
        if (sending > 0) {
            roundMapper.markWaitingResult(round.getId(), now);
            return;
        }
        if (mode == HyperlinkTaskMode.CYCLE) {
            completeAndScheduleNext(task, round, now);
            return;
        }
        replenish(task, round, now);
    }

    private void selectPlanned(HyperlinkTask task, HyperlinkTaskRound round, long now) {
        if (roundMapper.beginSelection(round.getId(), HyperlinkTaskRoundStatus.PLANNED.code(), now) != 1) {
            return;
        }
        int available = selectionService.select(task, round, now);
        finishSelection(round, available, now);
    }

    private void replenish(HyperlinkTask task, HyperlinkTaskRound round, long now) {
        int status = round.getRoundStatus();
        if (status != HyperlinkTaskRoundStatus.NO_ACCOUNT.code()
                && status != HyperlinkTaskRoundStatus.READY.code()
                && status != HyperlinkTaskRoundStatus.DISPATCHING.code()
                && status != HyperlinkTaskRoundStatus.WAITING_RESULT.code()) {
            return;
        }
        if (roundMapper.beginSelection(round.getId(), status, now) != 1) { return; }
        int available = selectionService.select(task, round, now);
        finishSelection(round, available, now);
        runtimeMapper.updateCurrentRound(task.getId(), round.getId(), round.getRoundNo(),
                available, now);
    }

    private void finishSelection(HyperlinkTaskRound round, int available, long now) {
        int selected = roundAccountMapper.countByRoundId(round.getId());
        int status = available == 0 ? HyperlinkTaskRoundStatus.NO_ACCOUNT.code()
                : HyperlinkTaskRoundStatus.READY.code();
        long dueAt = Math.max(round.getScheduledAt(), now);
        long nextDispatchAt = available == 0 ? dueAt + ACCOUNT_RECHECK_DELAY_MS : dueAt;
        roundMapper.updateSelection(round.getId(), selected, available, status, nextDispatchAt, now);
    }

    private void settleRound(HyperlinkTaskRound round, int sending, long now) {
        if (sending > 0 || usageMapper.countInFlight(round.getHyperlinkTaskId()) > 0) {
            roundMapper.markWaitingResult(round.getId(), now);
            return;
        }
        roundMapper.markCompleted(round.getId(), now);
    }

    private void completeAndScheduleNext(HyperlinkTask task, HyperlinkTaskRound round, long now) {
        if (roundMapper.markCompleted(round.getId(), now) != 1) { return; }
        HyperlinkTaskRound next = new HyperlinkTaskRound();
        long scheduledAt = Math.max(round.getScheduledAt()
                + task.getTaskIntervalMinutes() * 60_000L, now);
        next.setHyperlinkTaskId(task.getId());
        next.setRoundNo(round.getRoundNo() + 1);
        next.setRoundStatus(HyperlinkTaskRoundStatus.PLANNED.code());
        next.setScheduledAt(scheduledAt);
        next.setNextDispatchAt(scheduledAt);
        next.setAssignedRecipientCount(0);
        next.setSelectedAccountCount(0);
        next.setActualConcurrency(0);
        next.setVersion(1);
        next.setCreatedAt(now);
        next.setUpdatedAt(now);
        roundMapper.insert(next);
        runtimeMapper.updateCurrentRound(task.getId(), next.getId(), next.getRoundNo(), 0, now);
    }

    private HyperlinkTaskMode mode(Integer code) {
        for (HyperlinkTaskMode mode : HyperlinkTaskMode.values()) {
            if (Integer.valueOf(mode.code()).equals(code)) { return mode; }
        }
        throw new IllegalStateException("unsupported hyperlink task mode " + code);
    }

    private HyperlinkTaskRuntime lockRuntime(long taskId) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "超链轮次推进缺少租户上下文");
        }
        return runtimeMapper.selectByTaskIdForUpdate(tenantId, taskId);
    }
}
