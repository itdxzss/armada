package com.armada.marketing.service.impl;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.dto.MarketingNewGroupDTO;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.model.entity.MarketingTaskTarget;
import com.armada.marketing.model.enums.MarketingSendAttemptStatus;
import com.armada.marketing.model.enums.MarketingTaskStatus;
import com.armada.marketing.model.support.MarketingResolvedTarget;
import com.armada.marketing.model.support.MarketingSendAttemptResult;
import com.armada.marketing.scheduler.MarketingRoundSchedulerProperties;
import com.armada.marketing.service.MarketingMessageCommandFactory;
import com.armada.marketing.service.MarketingMessageComposer;
import com.armada.marketing.service.MarketingNewGroupImmediateSendService;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.port.MessageSendPort;
import com.armada.shared.exception.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 账号动态营销新群首次即时发送服务。
 *
 * <p>本服务只生成保留轮次 {@code round_no=0} 的 attempt，并复用普通营销消息端口写入
 * {@code protocol_command_outbox}。正常任务轮次号、下一轮时间和任务发送周期均不在本类修改。</p>
 */
@Service
public class MarketingNewGroupImmediateSendServiceImpl implements MarketingNewGroupImmediateSendService {
    private static final Logger log = LoggerFactory.getLogger(MarketingNewGroupImmediateSendServiceImpl.class);
    private static final long IMMEDIATE_ROUND_NO = 0L;
    private static final int INITIAL_ATTEMPT_NO = 1;
    private static final String REASON_INVALID_TEMPLATE_CONFIG = "INVALID_TEMPLATE_CONFIG";

    private final MarketingTaskMapper taskMapper;
    private final MarketingMessageCommandFactory messageFactory;
    private final MessageSendPort messageSendPort;
    private final MarketingRoundSchedulerProperties schedulerProperties;

    /**
     * 创建新群即时营销服务。
     *
     * @param taskMapper      营销任务 mapper
     * @param messageFactory  营销消息命令组装器
     * @param messageSendPort 统一消息发送端口
     * @param schedulerProperties 普通营销现有 outbox 分批配置
     */
    public MarketingNewGroupImmediateSendServiceImpl(MarketingTaskMapper taskMapper,
                                                     MarketingMessageCommandFactory messageFactory,
                                                     MessageSendPort messageSendPort,
                                                     MarketingRoundSchedulerProperties schedulerProperties) {
        this.taskMapper = taskMapper;
        this.messageFactory = messageFactory;
        this.messageSendPort = messageSendPort;
        this.schedulerProperties = schedulerProperties;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enqueueNewGroups(Long accountId,
                                 List<MarketingNewGroupDTO> groups,
                                 long detectedAt) {
        List<MarketingNewGroupDTO> candidates = normalizeGroups(groups);
        if (accountId == null || candidates.isEmpty()) {
            return;
        }
        MarketingTaskTarget target = taskMapper.selectOwnedSendingDynamicTarget(accountId, detectedAt);
        if (target == null) {
            return;
        }
        MarketingTask task = taskMapper.selectTaskById(target.getMarketingTaskId());
        if (!isSendingNow(task, detectedAt)) {
            return;
        }

        ClaimedImmediateTargets claimed = claimImmediateAttempts(
                task, target, candidates, detectedAt);
        if (claimed.attempts().isEmpty()) {
            return;
        }

        MarketingMessageComposer.ComposedMessage message;
        try {
            message = messageFactory.composeTaskMessage(task);
        } catch (BusinessException ex) {
            markLocalTemplateFailures(task, claimed.attempts(), ex.getMessage(), detectedAt);
            return;
        }
        enqueueClaimed(task, claimed.targets(), claimed.attempts(), message, detectedAt);
    }

    private ClaimedImmediateTargets claimImmediateAttempts(MarketingTask task,
                                                           MarketingTaskTarget target,
                                                           List<MarketingNewGroupDTO> candidates,
                                                           long detectedAt) {
        List<MarketingResolvedTarget> claimedTargets = new ArrayList<>();
        List<MarketingTaskSendAttempt> attempts = new ArrayList<>();
        for (MarketingNewGroupDTO group : candidates) {
            MarketingTaskSendAttempt attempt = immediateAttempt(task, target, group, detectedAt);
            try {
                if (taskMapper.insertSendAttempt(attempt) == 1) {
                    claimedTargets.add(new MarketingResolvedTarget(
                            target, group.groupLinkId(), group.groupJid(), group.groupName()));
                    attempts.add(attempt);
                }
            } catch (DuplicateKeyException duplicate) {
                log.debug("新群即时营销重复跳过 tenantId={} taskId={} accountId={} groupJid={}",
                        task.getTenantId(), task.getId(), target.getAccountId(), group.groupJid());
            }
        }
        return new ClaimedImmediateTargets(claimedTargets, attempts);
    }

    private MarketingTaskSendAttempt immediateAttempt(MarketingTask task,
                                                      MarketingTaskTarget target,
                                                      MarketingNewGroupDTO group,
                                                      long detectedAt) {
        MarketingTaskSendAttempt attempt = new MarketingTaskSendAttempt();
        attempt.setTenantId(task.getTenantId());
        attempt.setMarketingTaskId(task.getId());
        attempt.setTargetId(target.getId());
        attempt.setRoundNo(IMMEDIATE_ROUND_NO);
        attempt.setAttemptNo(INITIAL_ATTEMPT_NO);
        attempt.setRetry(false);
        attempt.setGroupLinkId(group.groupLinkId());
        attempt.setGroupJid(group.groupJid());
        attempt.setGroupName(group.groupName());
        attempt.setCommandId(messageFactory.newCommandId());
        attempt.setStatus(MarketingSendAttemptStatus.SUBMITTED.code());
        attempt.setSubmittedAt(detectedAt);
        attempt.setAttemptedAt(detectedAt);
        attempt.setCreatedAt(detectedAt);
        return attempt;
    }

    private void enqueueClaimed(MarketingTask task,
                                List<MarketingResolvedTarget> claimedTargets,
                                List<MarketingTaskSendAttempt> attempts,
                                MarketingMessageComposer.ComposedMessage message,
                                long detectedAt) {
        int intervalMs = messageFactory.accountGroupSendIntervalMs(task);
        List<MessageSendCommand> commands = new ArrayList<>(attempts.size());
        for (int index = 0; index < attempts.size(); index++) {
            long notBeforeAt = detectedAt + (long) index * intervalMs;
            commands.add(messageFactory.toCommand(
                    task, claimedTargets.get(index), attempts.get(index), message, notBeforeAt));
        }
        int batchSize = outboxBatchSize(message);
        int rejected = 0;
        for (int start = 0; start < commands.size(); start += batchSize) {
            int end = Math.min(commands.size(), start + batchSize);
            rejected += enqueueBatch(
                    commands.subList(start, end), attempts.subList(start, end), detectedAt);
        }
        if (rejected > 0) {
            taskMapper.incrementTaskSendCounters(task.getId(), 0, rejected, detectedAt);
        }
    }

    private int enqueueBatch(List<MessageSendCommand> commands,
                             List<MarketingTaskSendAttempt> attempts,
                             long detectedAt) {
        MessageSendEnqueueResult result = messageSendPort.enqueue(commands);
        if (result == null || result.items().size() != commands.size()) {
            throw new IllegalStateException("新群即时营销入队结果数量与命令不一致");
        }

        int rejected = 0;
        for (int index = 0; index < commands.size(); index++) {
            MessageSendCommand command = commands.get(index);
            MessageSendEnqueueItem item = result.items().get(index);
            if (item == null || !command.commandId().equals(item.commandId())) {
                throw new IllegalStateException("新群即时营销入队结果 commandId 与命令不一致");
            }
            if (!item.accepted() && finalizeLocalFailure(
                    attempts.get(index), item.reasonCode(), item.reasonMessage(), detectedAt)) {
                rejected++;
            }
        }
        return rejected;
    }

    private int outboxBatchSize(MarketingMessageComposer.ComposedMessage message) {
        int configured = messageFactory.hasLargeMediaPayload(message)
                ? schedulerProperties.getImageOutboxBatchSize()
                : schedulerProperties.getOutboxBatchSize();
        return Math.max(1, Math.min(500, configured));
    }

    private void markLocalTemplateFailures(MarketingTask task,
                                           List<MarketingTaskSendAttempt> attempts,
                                           String reasonMessage,
                                           long detectedAt) {
        int failed = 0;
        for (MarketingTaskSendAttempt attempt : attempts) {
            if (finalizeLocalFailure(
                    attempt, REASON_INVALID_TEMPLATE_CONFIG, reasonMessage, detectedAt)) {
                failed++;
            }
        }
        if (failed > 0) {
            taskMapper.incrementTaskSendCounters(task.getId(), 0, failed, detectedAt);
        }
    }

    private boolean finalizeLocalFailure(MarketingTaskSendAttempt attempt,
                                         String reasonCode,
                                         String reasonMessage,
                                         long resultAt) {
        MarketingSendAttemptResult result = new MarketingSendAttemptResult(
                attempt.getId(),
                attempt.getCommandId(),
                null,
                reasonCode,
                reasonMessage,
                attempt.getGroupJid(),
                null,
                null,
                null,
                resultAt);
        int updated = taskMapper.markAttemptFailed(result);
        if (updated == 0) {
            return false;
        }
        taskMapper.markTargetFailedFromAttempt(
                attempt.getTargetId(), attempt.getId(), reasonCode, reasonMessage, resultAt);
        return true;
    }

    private static List<MarketingNewGroupDTO> normalizeGroups(List<MarketingNewGroupDTO> groups) {
        if (groups == null || groups.isEmpty()) {
            return List.of();
        }
        Map<String, MarketingNewGroupDTO> unique = new LinkedHashMap<>();
        for (MarketingNewGroupDTO group : groups) {
            if (group == null || !StringUtils.hasText(group.groupJid())) {
                continue;
            }
            String groupJid = group.groupJid().trim();
            unique.putIfAbsent(groupJid,
                    new MarketingNewGroupDTO(group.groupLinkId(), groupJid, group.groupName()));
        }
        return List.copyOf(unique.values());
    }

    private static boolean isSendingNow(MarketingTask task, long now) {
        return task != null
                && Integer.valueOf(MarketingTaskStatus.SENDING.code()).equals(task.getStatus())
                && (task.getTaskStartAt() == null || task.getTaskStartAt() <= now)
                && (task.getTaskEndAt() == null || task.getTaskEndAt() > now);
    }

    /** 已通过唯一键抢占的实际群和对应 attempt，两个列表顺序严格一致。 */
    private record ClaimedImmediateTargets(
            List<MarketingResolvedTarget> targets,
            List<MarketingTaskSendAttempt> attempts) {
    }
}
