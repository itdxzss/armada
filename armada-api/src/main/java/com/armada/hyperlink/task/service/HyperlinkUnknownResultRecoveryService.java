package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskAccountUsage;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.hyperlink.task.model.enums.HyperlinkRecipientStatus;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskAccountUsageStatus;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskRunStatus;
import com.armada.hyperlink.task.model.vo.HyperlinkReconciliationCandidate;
import com.armada.platform.protocol.port.MessageCommandRecoveryPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.time.Clock;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SENDING 限时查询原 command，运行/暂停任务超时换号，已停止任务只收口。 */
@Service
public class HyperlinkUnknownResultRecoveryService {
    private static final long RETRY_DELAY_MS = 30_000L;
    private static final long MAX_WAIT_MS = 2 * 60_000L;
    private static final long BANNED_GRACE_MS = 2 * 60_000L;

    private final HyperlinkTaskRecipientMapper recipientMapper;
    private final MessageCommandRecoveryPort recoveryPort;
    private final HyperlinkAccountDispatchGuard dispatchGuard;
    private final HyperlinkTaskAccountUsageMapper usageMapper;
    private final HyperlinkMetricsProjectionService metrics;
    private final HyperlinkTaskRuntimeMapper runtimeMapper;
    private final Clock clock;

    @Autowired
    public HyperlinkUnknownResultRecoveryService(HyperlinkTaskRecipientMapper recipientMapper,
            MessageCommandRecoveryPort recoveryPort,
            HyperlinkAccountDispatchGuard dispatchGuard, HyperlinkTaskAccountUsageMapper usageMapper,
            HyperlinkMetricsProjectionService metrics, HyperlinkTaskRuntimeMapper runtimeMapper) {
        this(recipientMapper, recoveryPort, dispatchGuard, usageMapper, metrics, runtimeMapper, Clock.systemUTC());
    }

    HyperlinkUnknownResultRecoveryService(HyperlinkTaskRecipientMapper recipientMapper,
            MessageCommandRecoveryPort recoveryPort,
            HyperlinkAccountDispatchGuard dispatchGuard, HyperlinkTaskAccountUsageMapper usageMapper,
            HyperlinkMetricsProjectionService metrics, HyperlinkTaskRuntimeMapper runtimeMapper, Clock clock) {
        this.recipientMapper = recipientMapper;
        this.recoveryPort = recoveryPort;
        this.dispatchGuard = dispatchGuard;
        this.usageMapper = usageMapper;
        this.metrics = metrics;
        this.runtimeMapper = runtimeMapper;
        this.clock = clock;
    }

    /**
     * 用户选择接受未知重发的重复送达风险；超时在原任务内换号，不释放号码池归属。
     * 复用重试的 runtime→round→usage→recipient 锁顺序，旧命令回执不能结束新命令占用。
     */
    @Transactional(rollbackFor = Exception.class)
    public void recover(HyperlinkReconciliationCandidate candidate) {
        if (!Objects.equals(TenantContext.get(), candidate.tenantId())) {
            throw new BusinessException(ErrorCode.VALIDATION, "超链结果对账租户不匹配");
        }
        HyperlinkTaskRecipient observed = recipientMapper.selectByCommandId(candidate.commandId());
        if (observed == null || observed.getSendStatus() != HyperlinkRecipientStatus.SENDING.code()) { return; }
        if (!Objects.equals(observed.getHyperlinkTaskId(), candidate.taskId())
                || !Objects.equals(observed.getId(), candidate.recipientId())) {
            throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT);
        }
        var runtime = runtimeMapper.selectByTaskIdForUpdate(candidate.tenantId(), candidate.taskId());
        if (runtime == null) { throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT); }
        boolean retry = runtime.getRunStatus() == HyperlinkTaskRunStatus.RUNNING.code()
                || runtime.getRunStatus() == HyperlinkTaskRunStatus.PAUSED.code();
        if (retry) { metrics.lockRetryScope(observed); }
        HyperlinkTaskAccountUsage usage = usageMapper.selectByTaskAndAccountForUpdate(
                candidate.taskId(), candidate.accountId());
        HyperlinkTaskRecipient recipient = recipientMapper.selectByIdentityForUpdate(
                candidate.tenantId(), candidate.taskId(), candidate.recipientId(), candidate.commandId());
        if (recipient == null || recipient.getSendStatus() != HyperlinkRecipientStatus.SENDING.code()) { return; }
        if (usage == null || !Objects.equals(recipient.getAccountId(), candidate.accountId())) {
            throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT, "超链结果对账缺少账号占用事实");
        }
        long now = clock.millis();
        long startedAt = candidate.commandCreatedAt() == null ? 0 : candidate.commandCreatedAt();
        if (expired(startedAt, usage, now)) {
            if (retry) { requeueTimeout(recipient, usage, now); }
            else { closeTimeout(recipient, usage, now); }
            return;
        }
        dispatchGuard.renew(candidate.accountId(), candidate.commandId());
        recoveryPort.replay(candidate.tenantId(), candidate.commandId(), now);
        recipientMapper.scheduleReconciliation(candidate.commandId(), now + RETRY_DELAY_MS, now);
    }

    private void requeueTimeout(HyperlinkTaskRecipient recipient, HyperlinkTaskAccountUsage usage, long now) {
        recipient.setFailCode(Integer.valueOf(HyperlinkTaskAccountUsageStatus.BANNED.code()).equals(usage.getUsageStatus())
                ? HyperlinkRecipientStatus.BANNED_RESULT_TIMEOUT : HyperlinkRecipientStatus.RESULT_TIMEOUT);
        recipient.setFailReason("发送结果确认超时，等待其他发信人重试；原消息可能已送达");
        recipient.setUpdatedAt(now);
        recipient.setNextDispatchAt(now);
        recipientMapper.rememberRejectedSender(recipient);
        metrics.requeueSystemFailure(recipient);
        usageMapper.completeSlot(usage.getId(), false, now);
        dispatchGuard.releaseAfterCommit(recipient.getAccountId(), recipient.getCommandId(),
                recipient.getHyperlinkTaskId(), recipient.getId());
    }

    private boolean expired(long startedAt, HyperlinkTaskAccountUsage usage, long now) {
        return now - startedAt >= MAX_WAIT_MS
                || (Integer.valueOf(HyperlinkTaskAccountUsageStatus.BANNED.code()).equals(usage.getUsageStatus())
                && usage.getInvalidAt() != null && now - usage.getInvalidAt() >= BANNED_GRACE_MS);
    }

    private void closeTimeout(HyperlinkTaskRecipient recipient, HyperlinkTaskAccountUsage usage, long now) {
        boolean banned = Integer.valueOf(HyperlinkTaskAccountUsageStatus.BANNED.code()).equals(usage.getUsageStatus());
        recipient.setSendStatus(HyperlinkRecipientStatus.FAILED.code());
        recipient.setFailCode(banned ? HyperlinkRecipientStatus.BANNED_RESULT_TIMEOUT
                : HyperlinkRecipientStatus.RESULT_TIMEOUT);
        recipient.setFailReason(HyperlinkRecipientStatus.FAILED.businessMessage(recipient.getFailCode()));
        recipient.setUpdatedAt(now);
        if (recipientMapper.applyResult(recipient) == 1) {
            usageMapper.completeSlot(usage.getId(), false, now);
            dispatchGuard.releaseAfterCommit(recipient.getAccountId(), recipient.getCommandId(),
                    recipient.getHyperlinkTaskId(), recipient.getId());
        }
    }
}
