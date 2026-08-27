package com.armada.marketing.service.impl;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.model.entity.MarketingTaskTarget;
import com.armada.marketing.model.enums.MarketingSendAttemptStatus;
import com.armada.marketing.model.enums.MarketingBusinessType;
import com.armada.marketing.model.enums.MarketingTargetScope;
import com.armada.marketing.model.enums.MarketingTaskStatus;
import com.armada.marketing.model.support.MarketingResolvedTarget;
import com.armada.marketing.model.support.MarketingSendAttemptResult;
import com.armada.marketing.model.vo.MarketingAccountOccupancyOwnerRow;
import com.armada.marketing.model.vo.MarketingTargetCandidateRow;
import com.armada.marketing.service.MarketingMessageCommandFactory;
import com.armada.marketing.service.MarketingMessageComposer;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.port.MessageSendPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeAccess;
import com.armada.shared.security.DataScopeContext;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 新群首次即时营销的单次业务重试服务。
 *
 * <p>只允许 {@code round_no=0} 且仍处于第一次提交状态的 attempt 重试；重试复用原行并替换
 * commandId，使旧命令的迟到结果无法覆盖当前状态。</p>
 */
@Service
public class MarketingImmediateRetryService {
    private static final long IMMEDIATE_ROUND_NO = 0L;
    private static final int INITIAL_ATTEMPT_NO = 1;
    private static final int RETRY_ATTEMPT_NO = 2;
    private static final Set<String> BANNED_GROUP_CODES = Set.of(
            "GROUP_BANNED", "BANNED", "CHAT_SUSPENDED", "CHAT_TERMINATED");

    private final MarketingTaskMapper taskMapper;
    private final MarketingAccountOccupancyService occupancyService;
    private final MarketingMessageCommandFactory messageFactory;
    private final MessageSendPort messageSendPort;

    /**
     * 创建即时营销重试服务。
     *
     * @param taskMapper       营销任务 Mapper
     * @param occupancyService 账号占用服务
     * @param messageFactory   营销消息命令工厂
     * @param messageSendPort  协议无关消息发送端口
     */
    public MarketingImmediateRetryService(MarketingTaskMapper taskMapper,
                                          MarketingAccountOccupancyService occupancyService,
                                          MarketingMessageCommandFactory messageFactory,
                                          MessageSendPort messageSendPort) {
        this.taskMapper = taskMapper;
        this.occupancyService = occupancyService;
        this.messageFactory = messageFactory;
        this.messageSendPort = messageSendPort;
    }

    /**
     * 在失败结果仍对应第一次即时命令时，原子切换为第二次提交并重新入队。
     *
     * @param event    协议层失败结果
     * @param resultAt 失败结果时间(epoch 毫秒)
     * @return 已进入重试流程或已按本地拒绝终结时返回 true；不符合资格时返回 false
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean retryIfEligible(ProtocolMessageSendResultReportedEvent event, long resultAt) {
        if (event == null || !Long.valueOf(IMMEDIATE_ROUND_NO).equals(event.roundNo())) {
            return false;
        }
        if (reportsBannedGroup(event)) {
            return false;
        }
        MarketingTaskSendAttempt attempt = taskMapper.selectSendAttemptById(event.attemptId());
        if (!matchesFirstImmediateAttempt(attempt, event.commandId())) {
            return false;
        }
        MarketingTask task = taskMapper.selectTaskById(attempt.getMarketingTaskId());
        if (task == null || task.getOwnerUserId() == null) {
            return false;
        }
        DataScopeContext.current().ifPresent(scope ->
                DataScopeAccess.requireCanAccess(scope, task.getOwnerUserId(), "营销任务"));
        try (DataScopeContext.Scope ignored = DataScopeContext.open(
                DataScope.self(task.getOwnerUserId()))) {
            return retryOwnedTask(event, resultAt, attempt, task);
        }
    }

    private boolean retryOwnedTask(
            ProtocolMessageSendResultReportedEvent event,
            long resultAt,
            MarketingTaskSendAttempt attempt,
            MarketingTask task) {
        MarketingTaskTarget target = taskMapper.selectTargetById(attempt.getTargetId());
        if (!retryEnabledAndSending(task, target, resultAt)) {
            return false;
        }
        if (groupPull(task)) {
            if (taskMapper.countOwnedGroupPullMarketingGroup(task.getId()) != 1
                    || taskMapper.countSendableGroupPullTarget(target.getId()) != 1) {
                return false;
            }
        } else {
            MarketingAccountOccupancyOwnerRow owner = occupancyService
                    .loadActiveOwners(List.of(target.getAccountId()))
                    .get(target.getAccountId());
            if (owner == null || !Objects.equals(task.getId(), owner.getMarketingTaskId())) {
                return false;
            }
        }
        MarketingTargetCandidateRow group = taskMapper.selectCurrentTargetGroup(
                target.getAccountId(), attempt.getGroupLinkId());
        if (group == null || !Objects.equals(attempt.getGroupJid(), group.getGroupJid())) {
            return false;
        }
        return resubmit(
                event.commandId(),
                resultAt,
                new ImmediateRetryContext(task, target, attempt, group));
    }

    private boolean resubmit(String expectedCommandId,
                             long resultAt,
                             ImmediateRetryContext context) {
        MarketingTask task = context.task();
        MarketingTaskTarget target = context.target();
        MarketingTaskSendAttempt attempt = context.attempt();
        MarketingTargetCandidateRow group = context.group();
        MarketingMessageComposer.ComposedMessage message;
        try {
            message = messageFactory.composeTaskMessage(task);
        } catch (BusinessException ex) {
            return false;
        }
        String newCommandId = messageFactory.newCommandId();
        if (taskMapper.resubmitImmediateAttempt(
                attempt.getId(), expectedCommandId, newCommandId, resultAt) != 1) {
            return false;
        }
        taskMapper.incrementTargetRetryCount(target.getId(), attempt.getId(), resultAt);
        attempt.setAttemptNo(RETRY_ATTEMPT_NO);
        attempt.setRetry(true);
        attempt.setCommandId(newCommandId);
        attempt.setSubmittedAt(resultAt);
        attempt.setAttemptedAt(resultAt);
        MessageSendCommand command = messageFactory.toCommand(
                task,
                new MarketingResolvedTarget(
                        target, group.getGroupLinkId(), group.getGroupJid(), group.getGroupName()),
                attempt,
                message,
                resultAt);
        return enqueueRetryOrFinalizeLocalFailure(task, target, attempt, command, resultAt);
    }

    private static boolean matchesFirstImmediateAttempt(MarketingTaskSendAttempt attempt,
                                                        String commandId) {
        return attempt != null
                && Integer.valueOf(MarketingSendAttemptStatus.SUBMITTED.code()).equals(attempt.getStatus())
                && Long.valueOf(IMMEDIATE_ROUND_NO).equals(attempt.getRoundNo())
                && Integer.valueOf(INITIAL_ATTEMPT_NO).equals(attempt.getAttemptNo())
                && !Boolean.TRUE.equals(attempt.getRetry())
                && Objects.equals(attempt.getCommandId(), commandId);
    }

    private static boolean retryEnabledAndSending(MarketingTask task,
                                                  MarketingTaskTarget target,
                                                  long now) {
        return task != null
                && target != null
                && target.getAccountId() != null
                && Objects.equals(task.getId(), target.getMarketingTaskId())
                && Boolean.TRUE.equals(task.getAutoRetryEnabled())
                && task.getRetryLimit() != null
                && task.getRetryLimit() >= 1
                && Integer.valueOf(MarketingTaskStatus.SENDING.code()).equals(task.getStatus())
                && validTargetScope(task, target)
                && (task.getTaskStartAt() == null || task.getTaskStartAt() <= now)
                && (task.getTaskEndAt() == null || task.getTaskEndAt() > now);
    }

    private static boolean validTargetScope(MarketingTask task, MarketingTaskTarget target) {
        if (groupPull(task)) {
            return Integer.valueOf(MarketingTargetScope.GROUP_FIXED.code())
                    .equals(target.getTargetScope());
        }
        return Integer.valueOf(MarketingTargetScope.ACCOUNT_DYNAMIC.code())
                .equals(target.getTargetScope());
    }

    private static boolean groupPull(MarketingTask task) {
        return task != null
                && Integer.valueOf(MarketingBusinessType.GROUP_PULL.code())
                        .equals(task.getBusinessType());
    }

    private static boolean reportsBannedGroup(ProtocolMessageSendResultReportedEvent event) {
        return bannedCode(event.reasonCode())
                || bannedCode(event.groupStatus())
                || bannedCode(event.groupStatusReason());
    }

    private static boolean bannedCode(String value) {
        return value != null
                && BANNED_GROUP_CODES.contains(value.trim().toUpperCase(Locale.ROOT));
    }

    private boolean enqueueRetryOrFinalizeLocalFailure(MarketingTask task,
                                                       MarketingTaskTarget target,
                                                       MarketingTaskSendAttempt attempt,
                                                       MessageSendCommand command,
                                                       long resultAt) {
        MessageSendEnqueueResult enqueueResult = messageSendPort.enqueue(List.of(command));
        if (enqueueResult == null || enqueueResult.items().size() != 1) {
            throw new IllegalStateException("即时营销重试入队结果数量与命令不一致");
        }
        MessageSendEnqueueItem item = enqueueResult.items().get(0);
        if (item == null || !command.commandId().equals(item.commandId())) {
            throw new IllegalStateException("即时营销重试入队结果 commandId 与命令不一致");
        }
        if (item.accepted()) {
            return true;
        }
        finalizeLocalFailure(task, target, attempt, item, resultAt);
        return true;
    }

    private void finalizeLocalFailure(MarketingTask task,
                                      MarketingTaskTarget target,
                                      MarketingTaskSendAttempt attempt,
                                      MessageSendEnqueueItem item,
                                      long resultAt) {
        MarketingSendAttemptResult result = new MarketingSendAttemptResult(
                attempt.getId(),
                attempt.getCommandId(),
                null,
                item.reasonCode(),
                item.reasonMessage(),
                attempt.getGroupJid(),
                null,
                null,
                null,
                resultAt);
        if (taskMapper.markAttemptFailed(result) <= 0) {
            return;
        }
        taskMapper.markTargetFailedFromAttempt(
                target.getId(), attempt.getId(), item.reasonCode(), item.reasonMessage(), resultAt);
        taskMapper.incrementTaskSendCounters(task.getId(), 0, 1, resultAt);
    }

    private record ImmediateRetryContext(
            MarketingTask task,
            MarketingTaskTarget target,
            MarketingTaskSendAttempt attempt,
            MarketingTargetCandidateRow group
    ) {
    }
}
