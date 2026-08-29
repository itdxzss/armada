package com.armada.hyperlink.task.service;

import com.armada.account.service.AccountHyperlinkCandidateService;
import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskContentMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskAccountUsage;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskContent;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRound;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime;
import com.armada.hyperlink.task.model.enums.HyperlinkRecipientStatus;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskRoundStatus;
import com.armada.hyperlink.data.model.enums.DataPackagePoolStatus;
import com.armada.hyperlink.data.service.DataPackageRecipientClaimService;
import com.armada.hyperlink.task.port.HyperlinkPrivateCapabilityPort;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.port.MessageSendPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 账号占槽 → recipient 唯一 command → 协议 outbox 的同事务派发链。 */
@Service
public class HyperlinkDispatchService {
    private static final Logger log = LoggerFactory.getLogger(HyperlinkDispatchService.class);
    private static final int SHORT_CODE_INSERT_ATTEMPTS = 8;
    private static final long RESULT_RECONCILIATION_DELAY_MS = 30_000L;
    private static final long GLOBAL_CAPACITY_RETRY_DELAY_MS = 30_000L;
    private final HyperlinkTaskMapper taskMapper;
    private final HyperlinkTaskContentMapper contentMapper;
    private final HyperlinkTaskRuntimeMapper runtimeMapper;
    private final HyperlinkTaskRoundMapper roundMapper;
    private final HyperlinkTaskAccountUsageMapper usageMapper;
    private final HyperlinkTaskRecipientMapper recipientMapper;
    private final HyperlinkMessageCommandFactory commandFactory;
    private final HyperlinkShortCodeGenerator shortCodeGenerator;
    private final HyperlinkPrivateCapabilityPort capabilityPort;
    private final MessageSendPort messageSendPort;
    private final DataPackageRecipientClaimService dataPackageRecipientClaimService;
    private final AccountHyperlinkCandidateService accountHyperlinkCandidateService;
    private final HyperlinkAccountDispatchGuard dispatchGuard;
    private final Clock clock;

    public HyperlinkDispatchService(HyperlinkTaskMapper taskMapper,
            HyperlinkTaskContentMapper contentMapper, HyperlinkTaskRuntimeMapper runtimeMapper,
            HyperlinkTaskRoundMapper roundMapper, HyperlinkTaskAccountUsageMapper usageMapper,
            HyperlinkTaskRecipientMapper recipientMapper,
            HyperlinkMessageCommandFactory commandFactory, HyperlinkShortCodeGenerator shortCodeGenerator,
            HyperlinkPrivateCapabilityPort capabilityPort, MessageSendPort messageSendPort,
            DataPackageRecipientClaimService dataPackageRecipientClaimService,
            AccountHyperlinkCandidateService accountHyperlinkCandidateService,
            HyperlinkAccountDispatchGuard dispatchGuard) {
        this(taskMapper, contentMapper, runtimeMapper, roundMapper, usageMapper, recipientMapper,
                commandFactory, shortCodeGenerator, capabilityPort, messageSendPort,
                dataPackageRecipientClaimService, accountHyperlinkCandidateService,
                dispatchGuard, Clock.systemUTC());
    }

    HyperlinkDispatchService(HyperlinkTaskMapper taskMapper,
            HyperlinkTaskContentMapper contentMapper, HyperlinkTaskRuntimeMapper runtimeMapper,
            HyperlinkTaskRoundMapper roundMapper, HyperlinkTaskAccountUsageMapper usageMapper,
            HyperlinkTaskRecipientMapper recipientMapper,
            HyperlinkMessageCommandFactory commandFactory, HyperlinkShortCodeGenerator shortCodeGenerator,
            HyperlinkPrivateCapabilityPort capabilityPort, MessageSendPort messageSendPort,
            DataPackageRecipientClaimService dataPackageRecipientClaimService,
            AccountHyperlinkCandidateService accountHyperlinkCandidateService,
            HyperlinkAccountDispatchGuard dispatchGuard, Clock clock) {
        this.taskMapper = taskMapper;
        this.contentMapper = contentMapper;
        this.runtimeMapper = runtimeMapper;
        this.roundMapper = roundMapper;
        this.usageMapper = usageMapper;
        this.recipientMapper = recipientMapper;
        this.commandFactory = commandFactory;
        this.shortCodeGenerator = shortCodeGenerator;
        this.capabilityPort = capabilityPort;
        this.messageSendPort = messageSendPort;
        this.dataPackageRecipientClaimService = dataPackageRecipientClaimService;
        this.accountHyperlinkCandidateService = accountHyperlinkCandidateService;
        this.dispatchGuard = dispatchGuard;
        this.clock = clock;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean dispatchOne(long taskId) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "超链派发缺少租户上下文");
        }
        HyperlinkTaskRuntime runtime = runtimeMapper.selectByTaskIdForShare(tenantId, taskId);
        if (runtime == null || runtime.getRunStatus() != 1) { return false; }
        HyperlinkTaskRound round = roundMapper.selectActiveForUpdate(tenantId, taskId);
        if (round == null || (round.getRoundStatus() != HyperlinkTaskRoundStatus.READY.code()
                && round.getRoundStatus() != HyperlinkTaskRoundStatus.DISPATCHING.code())) {
            return false;
        }
        HyperlinkTask task = taskMapper.selectById(taskId);
        if (task == null) { return false; }
        long now = clock.millis();
        for (HyperlinkTaskAccountUsage usage : usageMapper.selectAvailable(taskId, round.getId(),
                now, task.getAccountSendConcurrency(), 20)) {
            ProtocolBackend backend = usage.getProtocolBackend() == 2
                    ? ProtocolBackend.ANDROID : ProtocolBackend.WEB;
            if (!capabilityPort.supports(backend, usage.getProtocolIdSnapshot())) { continue; }
            if (usageMapper.reserveSlot(usage.getId(), usage.getVersion(),
                    task.getAccountSendConcurrency(), now) != 1) { continue; }
            if (!accountHyperlinkCandidateService.lockForHyperlinkDispatch(
                    usage.getAccountId())) {
                delayForGlobalCapacity(usage, now);
                return true;
            }
            List<Long> sendingIds = recipientMapper.lockSendingIdsByAccount(
                    tenantId, usage.getAccountId(), HyperlinkAccountDispatchGuard.MAX_IN_FLIGHT);
            if (sendingIds.size() >= HyperlinkAccountDispatchGuard.MAX_IN_FLIGHT) {
                delayForGlobalCapacity(usage, now);
                return true;
            }
            HyperlinkTaskRecipient recipient = recipientMapper.lockPending(
                    tenantId, taskId, round.getId(), now);
            if (recipient == null) {
                usageMapper.completeSlot(usage.getId(), false, now);
                return false;
            }
            String commandId = commandFactory.commandId(task.getTenantId(), taskId, recipient.getId());
            if (!dispatchGuard.tryAcquire(usage.getAccountId(), commandId)) {
                delayForGlobalCapacity(usage, now);
                return true;
            }
            AtomicBoolean retainAfterCommit = registerGuardCleanup(
                    usage.getAccountId(), commandId);
            long nextSendAt = now + interval(task, recipient.getId());
            try {
                requireScheduled(usageMapper.scheduleNextSend(usage.getId(), nextSendAt, now));
                recipient.setHyperlinkTaskRoundId(round.getId());
                recipient.setRoundNo(round.getRoundNo());
                recipient.setAccountId(usage.getAccountId());
                recipient.setSenderPhoneSnapshot(usage.getAccountPhoneSnapshot());
                recipient.setSenderCountryIso2Snapshot(usage.getSenderCountryIso2Snapshot());
                recipient.setSenderAccountTypeSnapshot(usage.getAccountTypeSnapshot());
                recipient.setProtocolId(usage.getProtocolIdSnapshot());
                recipient.setProtocolBackend(usage.getProtocolBackend());
                recipient.setCommandId(commandId);
                recipient.setNextDispatchAt(nextSendAt);
                recipient.setUpdatedAt(now);
                assignCommand(task, recipient);
                HyperlinkTaskContent content = contentMapper.selectByTaskId(taskId);
                MessageSendCommand command = commandFactory.create(task, content, recipient, usage, now);
                MessageSendEnqueueResult result = messageSendPort.enqueue(List.of(command));
                MessageSendEnqueueItem item = result == null || result.items().size() != 1
                        ? null : result.items().get(0);
                if (item == null || !item.accepted()) {
                    String code = item == null ? "LOCAL_ADAPTER_REJECTED" : item.reasonCode();
                    String reason = item == null ? "本地协议适配器拒绝" : item.reasonMessage();
                    recipient.setSendStatus(HyperlinkRecipientStatus.FAILED.code());
                    recipient.setProtocolMessageId(null);
                    recipient.setFailCode(code);
                    recipient.setFailReason(reason);
                    recipientMapper.applyResult(recipient);
                    usageMapper.completeSlot(usage.getId(), false, now);
                    dataPackageRecipientClaimService.advanceDeliveryFact(taskId,
                            recipient.getDataPackageId(), recipient.getDataPackageGeneration(),
                            recipient.getRecipientPhoneSnapshot(),
                            DataPackagePoolStatus.RETRYABLE_FAILED, now);
                } else {
                    recipientMapper.markSubmitted(commandId, now,
                            now + RESULT_RECONCILIATION_DELAY_MS);
                    roundMapper.markDispatching(round.getId(), now);
                    retainAfterCommit.set(true);
                }
                return true;
            } finally {
                if (!TransactionSynchronizationManager.isSynchronizationActive()
                        && !retainAfterCommit.get()) {
                    dispatchGuard.release(usage.getAccountId(), commandId);
                }
            }
        }
        return false;
    }

    private void delayForGlobalCapacity(HyperlinkTaskAccountUsage usage, long now) {
        usageMapper.completeSlot(usage.getId(), false, now);
        requireScheduled(usageMapper.scheduleNextSend(
                usage.getId(), now + GLOBAL_CAPACITY_RETRY_DELAY_MS, now));
    }

    private AtomicBoolean registerGuardCleanup(long accountId, String commandId) {
        AtomicBoolean retainAfterCommit = new AtomicBoolean(false);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (status != STATUS_COMMITTED || !retainAfterCommit.get()) {
                                try {
                                    dispatchGuard.release(accountId, commandId);
                                } catch (BusinessException exception) {
                                    log.error("hyperlink uncommitted account holder release failed accountId={}",
                                            accountId, exception);
                                }
                            }
                        }
                    });
        }
        return retainAfterCommit;
    }

    private void requireScheduled(int updated) {
        if (updated != 1) {
            throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT,
                    "账号派发间隔更新失败");
        }
    }

    private void assignCommand(HyperlinkTask task, HyperlinkTaskRecipient recipient) {
        if (!Boolean.TRUE.equals(task.getShortLinkEnabled())) {
            recipient.setShortCode(null);
            requireAssigned(recipientMapper.assignCommand(recipient));
            return;
        }
        for (int attempt = 0; attempt < SHORT_CODE_INSERT_ATTEMPTS; attempt++) {
            recipient.setShortCode(shortCodeGenerator.next());
            try {
                requireAssigned(recipientMapper.assignCommand(recipient));
                return;
            } catch (DuplicateKeyException collision) {
                if (attempt + 1 == SHORT_CODE_INSERT_ATTEMPTS) {
                    throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT,
                            "短链码生成冲突，请稍后重试");
                }
            }
        }
    }

    private void requireAssigned(int updated) {
        if (updated != 1) {
            throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT,
                    "recipient 已被并发派发");
        }
    }

    private long interval(HyperlinkTask task, long recipientId) {
        int range = task.getMsgIntervalMaxMs() - task.getMsgIntervalMinMs();
        return task.getMsgIntervalMinMs() + (range == 0 ? 0 : Math.floorMod(recipientId, range + 1));
    }
}
