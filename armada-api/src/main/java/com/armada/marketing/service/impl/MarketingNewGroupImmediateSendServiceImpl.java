package com.armada.marketing.service.impl;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.dto.MarketingNewGroupDTO;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.model.entity.MarketingTaskTarget;
import com.armada.marketing.model.enums.MarketingSendAttemptStatus;
import com.armada.marketing.model.enums.MarketingBusinessType;
import com.armada.marketing.model.enums.MarketingNewGroupDelayUnit;
import com.armada.marketing.model.enums.MarketingTaskStatus;
import com.armada.marketing.model.enums.MarketingTargetScope;
import com.armada.marketing.model.support.MarketingResolvedTarget;
import com.armada.marketing.model.support.MarketingSendAttemptResult;
import com.armada.marketing.model.vo.MarketingAccountOccupancyOwnerRow;
import com.armada.marketing.model.vo.MarketingTargetCandidateRow;
import com.armada.marketing.scheduler.MarketingRoundSchedulerProperties;
import com.armada.marketing.service.MarketingMessageCommandFactory;
import com.armada.marketing.service.MarketingMessageComposer;
import com.armada.marketing.service.MarketingNewGroupImmediateSendService;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.port.MessageSendPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /** 安全日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(MarketingNewGroupImmediateSendServiceImpl.class);

    /** 新群首次即时发送使用的保留轮次号。 */
    private static final long IMMEDIATE_ROUND_NO = 0L;

    /** 新群首次即时发送的初始尝试次数。 */
    private static final int INITIAL_ATTEMPT_NO = 1;

    /** 模板配置无法生成消息时写入发送明细的稳定失败码。 */
    private static final String REASON_INVALID_TEMPLATE_CONFIG = "INVALID_TEMPLATE_CONFIG";
    private static final String REASON_ORDINARY_ROUND_COVERED = "ORDINARY_ROUND_COVERED";
    private static final String REASON_TASK_CLOSED = "TASK_CLOSED";
    private static final String REASON_TASK_EXPIRED = "TASK_EXPIRED";
    private static final String REASON_ACCOUNT_NOT_OWNED = "ACCOUNT_NOT_OWNED";
    private static final String REASON_ACCOUNT_NOT_ELIGIBLE = "ACCOUNT_NOT_ELIGIBLE";
    private static final String REASON_GROUP_NOT_SENDABLE = "GROUP_NOT_SENDABLE";

    /** 营销任务、目标和发送尝试数据访问。 */
    private final MarketingTaskMapper taskMapper;

    /** 营销消息内容及协议命令组装器。 */
    private final MarketingMessageCommandFactory messageFactory;

    /** 统一消息发送端口，负责持久化协议 outbox。 */
    private final MessageSendPort messageSendPort;

    /** 复用普通营销 outbox 分批参数。 */
    private final MarketingRoundSchedulerProperties schedulerProperties;

    /** 普通营销账号占用关系，用于到期时重新确认任务仍持有账号。 */
    private final MarketingAccountOccupancyService occupancyService;

    /**
     * 创建新群即时营销服务。
     *
     * @param taskMapper      营销任务 mapper
     * @param messageFactory  营销消息命令组装器
     * @param messageSendPort 统一消息发送端口
     * @param schedulerProperties 普通营销现有 outbox 分批配置
     * @param occupancyService 普通营销账号占用服务
     */
    public MarketingNewGroupImmediateSendServiceImpl(MarketingTaskMapper taskMapper,
                                                     MarketingMessageCommandFactory messageFactory,
                                                     MessageSendPort messageSendPort,
                                                     MarketingRoundSchedulerProperties schedulerProperties,
                                                     MarketingAccountOccupancyService occupancyService) {
        this.taskMapper = taskMapper;
        this.messageFactory = messageFactory;
        this.messageSendPort = messageSendPort;
        this.schedulerProperties = schedulerProperties;
        this.occupancyService = occupancyService;
    }

    /**
     * 为账号动态目标本次新发现的群组生成第 0 轮即时发送。
     *
     * <p>方法先按群 JID 清洗去重，再通过发送尝试唯一键抢占实际需要发送的群；重复检测不会产生重复消息，
     * 且不会推进任务正常轮次或修改下一轮执行时间。</p>
     *
     * @param accountId 发现新群的 Armada 账号 ID
     * @param groups 本次检测到的新群，按检测顺序排列
     * @param detectedAt 新群检测时间（epoch 毫秒）
     */
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
        MarketingTask task = taskMapper.selectTaskByIdForUpdate(target.getMarketingTaskId());
        if (!canRegisterNewGroup(task, detectedAt)) {
            return;
        }

        boolean delayed = delayEnabled(task);
        ClaimedImmediateTargets claimed = delayed
                ? claimWaitingAttempts(task, target, candidates, detectedAt)
                : claimImmediateAttempts(task, target, candidates, detectedAt);
        if (claimed.attempts().isEmpty()) {
            return;
        }
        if (delayed) {
            log.info("新群首次营销已进入等待 tenantId={} taskId={} accountId={} groups={} scheduledSendAt={}",
                    task.getTenantId(), task.getId(), target.getAccountId(), claimed.attempts().size(),
                    claimed.attempts().get(0).getScheduledSendAt());
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

    /**
     * 为实时成员新增事件只登记延迟任务 WAITING，不改变延迟关闭任务的即时发送语义。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enqueueDelayedNewGroups(Long accountId,
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
        MarketingTask candidateTask = taskMapper.selectTaskById(target.getMarketingTaskId());
        if (candidateTask == null || !delayEnabled(candidateTask)) {
            return;
        }
        MarketingTask task = taskMapper.selectTaskByIdForUpdate(target.getMarketingTaskId());
        if (!canRegisterNewGroup(task, detectedAt) || !delayEnabled(task)) {
            return;
        }
        ClaimedImmediateTargets claimed = claimWaitingAttempts(task, target, candidates, detectedAt);
        if (!claimed.attempts().isEmpty()) {
            log.info("群成员增量新群已进入延迟等待 tenantId={} taskId={} accountId={} groups={} scheduledSendAt={}",
                    task.getTenantId(), task.getId(), target.getAccountId(), claimed.attempts().size(),
                    claimed.attempts().get(0).getScheduledSendAt());
        }
    }

    /**
     * 在当前租户事务中提交已到期的第 0 轮等待记录。
     *
     * <p>暂停状态保留 WAITING；关闭、结束、普通轮次覆盖或发送资格失效时转为 SKIPPED。
     * 只有 Outbox 接受命令后才从 WAITING 转为 SUBMITTED，事务失败时二者一起回滚。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitDueWaitingAttempts(Long tenantId,
                                         Long marketingTaskId,
                                         List<Long> attemptIds,
                                         long submittedAt) {
        if (tenantId == null || marketingTaskId == null || attemptIds == null || attemptIds.isEmpty()) {
            return;
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            submitDueWaitingAttemptsInTenant(
                    tenantId,
                    marketingTaskId,
                    attemptIds.stream().filter(Objects::nonNull).distinct().toList(),
                    submittedAt);
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    private void submitDueWaitingAttemptsInTenant(Long tenantId,
                                                   Long marketingTaskId,
                                                   List<Long> attemptIds,
                                                   long submittedAt) {
        if (attemptIds.isEmpty()) {
            return;
        }
        MarketingTask task = taskMapper.selectTaskByIdForUpdate(marketingTaskId);
        if (task == null) {
            return;
        }
        List<MarketingTaskSendAttempt> waiting = taskMapper.selectWaitingAttemptsForUpdate(
                tenantId, marketingTaskId, attemptIds, submittedAt);
        if (waiting.isEmpty()) {
            return;
        }
        if (Integer.valueOf(MarketingTaskStatus.CLOSED.code()).equals(task.getStatus())) {
            skipAttempts(waiting, REASON_TASK_CLOSED, "营销任务已关闭", submittedAt);
            return;
        }
        if (Integer.valueOf(MarketingTaskStatus.PAUSED.code()).equals(task.getStatus())
                || Integer.valueOf(MarketingTaskStatus.PENDING.code()).equals(task.getStatus())) {
            return;
        }
        if (!Integer.valueOf(MarketingTaskStatus.SENDING.code()).equals(task.getStatus())
                || task.getTaskEndAt() != null && task.getTaskEndAt() <= submittedAt) {
            skipAttempts(waiting, REASON_TASK_EXPIRED, "营销任务已结束", submittedAt);
            return;
        }
        MarketingTaskTarget target = taskMapper.selectTargetById(waiting.get(0).getTargetId());
        if (!validDynamicTarget(task, target, waiting)) {
            skipAttempts(waiting, REASON_GROUP_NOT_SENDABLE, "新群不再属于当前账号动态目标", submittedAt);
            return;
        }
        List<MarketingTaskSendAttempt> uncovered = skipOrdinaryCovered(waiting, submittedAt);
        if (uncovered.isEmpty()) {
            return;
        }
        MarketingAccountOccupancyOwnerRow owner = occupancyService
                .loadActiveOwners(List.of(target.getAccountId()))
                .get(target.getAccountId());
        if (owner == null || !task.getId().equals(owner.getMarketingTaskId())) {
            skipAttempts(uncovered, REASON_ACCOUNT_NOT_OWNED, "账号不再由当前营销任务占用", submittedAt);
            return;
        }
        if (taskMapper.selectAccountTargetCandidate(
                task.getAccountGroupId(), target.getAccountId(), MarketingAccountEligibility.selectableAccountStates())
                == null) {
            skipAttempts(uncovered, REASON_ACCOUNT_NOT_ELIGIBLE, "账号当前不可发送", submittedAt);
            return;
        }
        ClaimedImmediateTargets sendable = dueSendableTargets(target, uncovered, submittedAt);
        if (sendable.attempts().isEmpty()) {
            return;
        }
        MarketingMessageComposer.ComposedMessage message;
        try {
            message = messageFactory.composeTaskMessage(task);
        } catch (BusinessException ex) {
            markLocalTemplateFailures(task, sendable.attempts(), ex.getMessage(), submittedAt);
            return;
        }
        for (MarketingTaskSendAttempt attempt : sendable.attempts()) {
            attempt.setCommandId(messageFactory.newCommandId());
        }
        enqueueClaimed(task, sendable.targets(), sendable.attempts(), message, submittedAt);
    }

    /**
     * 为拉群营销刚创建成功的固定群目标生成第 0 轮即时发送。
     *
     * <p>仅任务仍在执行、目标属于该拉群任务且营销账号和群状态可发送时创建 attempt；唯一键冲突表示
     * 该群已经首发，直接幂等跳过。</p>
     *
     * @param marketingTaskId 拉群营销统一任务 ID
     * @param targetId 建群成功后创建的固定营销目标 ID
     * @param detectedAt 建群成功时间（epoch 毫秒）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enqueueFixedTarget(Long marketingTaskId, Long targetId, long detectedAt) {
        if (marketingTaskId == null || targetId == null) {
            return;
        }
        MarketingTask task = taskMapper.selectTaskById(marketingTaskId);
        MarketingTaskTarget target = taskMapper.selectTargetById(targetId);
        if (!isSendableGroupPullTarget(task, target, marketingTaskId, detectedAt)
                || taskMapper.countSendableGroupPullTarget(targetId) != 1) {
            return;
        }
        MarketingNewGroupDTO group = new MarketingNewGroupDTO(
                target.getGroupLinkId(), target.getGroupJid(), target.getGroupName());
        MarketingTaskSendAttempt attempt = immediateAttempt(task, target, group, detectedAt);
        try {
            if (taskMapper.insertSendAttempt(attempt) != 1) {
                return;
            }
        } catch (DuplicateKeyException duplicate) {
            log.debug(
                    "拉群营销固定群首发重复跳过 tenantId={} taskId={} targetId={}",
                    task.getTenantId(), task.getId(), targetId);
            return;
        }

        MarketingMessageComposer.ComposedMessage message;
        try {
            message = messageFactory.composeTaskMessage(task);
        } catch (BusinessException exception) {
            markLocalTemplateFailures(task, List.of(attempt), exception.getMessage(), detectedAt);
            return;
        }
        enqueueClaimed(
                task,
                List.of(new MarketingResolvedTarget(
                        target, target.getGroupLinkId(), target.getGroupJid(), target.getGroupName())),
                List.of(attempt),
                message,
                detectedAt);
    }

    /**
     * 通过发送尝试唯一键抢占账号动态目标中尚未首发的群。
     *
     * @param task 当前发送中的营销任务
     * @param target 账号动态营销目标
     * @param candidates 规范化且去重后的新群
     * @param detectedAt 新群检测时间（epoch 毫秒）
     * @return 顺序严格对齐的已抢占群目标和发送尝试
     */
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

    private ClaimedImmediateTargets claimWaitingAttempts(MarketingTask task,
                                                         MarketingTaskTarget target,
                                                         List<MarketingNewGroupDTO> candidates,
                                                         long detectedAt) {
        List<MarketingResolvedTarget> claimedTargets = new ArrayList<>();
        List<MarketingTaskSendAttempt> attempts = new ArrayList<>();
        long scheduledSendAt = detectedAt + delayMilliseconds(task);
        for (MarketingNewGroupDTO group : candidates) {
            MarketingTaskSendAttempt attempt = waitingAttempt(task, target, group, detectedAt, scheduledSendAt);
            try {
                if (taskMapper.insertSendAttempt(attempt) == 1) {
                    claimedTargets.add(new MarketingResolvedTarget(
                            target, group.groupLinkId(), group.groupJid(), group.groupName()));
                    attempts.add(attempt);
                }
            } catch (DuplicateKeyException duplicate) {
                log.debug("新群延迟营销重复跳过 tenantId={} taskId={} accountId={} groupJid={}",
                        task.getTenantId(), task.getId(), target.getAccountId(), group.groupJid());
            }
        }
        return new ClaimedImmediateTargets(claimedTargets, attempts);
    }

    /**
     * 创建一条尚未下发的第 0 轮发送尝试实体。
     *
     * @param task 当前营销任务
     * @param target 当前任务目标
     * @param group 本次实际发送的群快照
     * @param detectedAt 新群检测或建群成功时间（epoch 毫秒）
     * @return 待插入的发送尝试实体
     */
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
        attempt.setDetectedAt(detectedAt);
        attempt.setScheduledSendAt(detectedAt);
        attempt.setSubmittedAt(detectedAt);
        attempt.setAttemptedAt(detectedAt);
        attempt.setCreatedAt(detectedAt);
        return attempt;
    }

    private MarketingTaskSendAttempt waitingAttempt(MarketingTask task,
                                                    MarketingTaskTarget target,
                                                    MarketingNewGroupDTO group,
                                                    long detectedAt,
                                                    long scheduledSendAt) {
        MarketingTaskSendAttempt attempt = immediateAttempt(task, target, group, detectedAt);
        attempt.setCommandId(null);
        attempt.setStatus(MarketingSendAttemptStatus.WAITING.code());
        attempt.setScheduledSendAt(scheduledSendAt);
        attempt.setSubmittedAt(null);
        return attempt;
    }

    /**
     * 将已抢占的发送尝试转换为协议命令并按 outbox 配置分批入队。
     *
     * <p>拉群营销由命令工厂返回零群间隔；账号动态营销继续沿用任务配置的群间隔。</p>
     *
     * @param task 当前营销任务
     * @param claimedTargets 已抢占的实际群目标
     * @param attempts 与群目标顺序对齐的发送尝试
     * @param message 已组合的营销消息
     * @param detectedAt 新群检测或建群成功时间（epoch 毫秒）
     */
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

    /**
     * 下发单个 outbox 批次并把协议拒绝结果立即收口为本地失败。
     *
     * @param commands 本批协议发送命令
     * @param attempts 与命令顺序严格对齐的发送尝试
     * @param detectedAt 本批新群检测时间（epoch 毫秒）
     * @return 本批首次成功写入失败终态的数量
     */
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
            MarketingTaskSendAttempt attempt = attempts.get(index);
            if (item.accepted()) {
                submitWaitingAttemptIfNeeded(attempt, command.commandId(), detectedAt);
            } else if (finalizeLocalFailure(
                    attempt, item.reasonCode(), item.reasonMessage(), detectedAt)) {
                rejected++;
            }
        }
        return rejected;
    }

    /**
     * 根据消息是否包含大媒体载荷选择安全的 outbox 分批大小。
     *
     * @param message 已组合的营销消息
     * @return 限制在 1 到 500 之间的批次大小
     */
    private int outboxBatchSize(MarketingMessageComposer.ComposedMessage message) {
        int configured = messageFactory.hasLargeMediaPayload(message)
                ? schedulerProperties.getImageOutboxBatchSize()
                : schedulerProperties.getOutboxBatchSize();
        return Math.max(1, Math.min(500, configured));
    }

    /**
     * 将本地模板组装失败写入全部已抢占发送尝试并累加任务失败数。
     *
     * @param task 当前营销任务
     * @param attempts 已抢占的发送尝试
     * @param reasonMessage 模板校验失败原因
     * @param detectedAt 失败发生时间（epoch 毫秒）
     */
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

    /**
     * 幂等写入一条尚未完成的发送尝试失败结果，并同步目标最后失败原因。
     *
     * @param attempt 待收口的发送尝试
     * @param reasonCode 稳定失败码
     * @param reasonMessage 失败详情
     * @param resultAt 失败发生时间（epoch 毫秒）
     * @return 本次实际写入失败终态时返回 {@code true}
     */
    private boolean finalizeLocalFailure(MarketingTaskSendAttempt attempt,
                                         String reasonCode,
                                         String reasonMessage,
                                         long resultAt) {
        if (Integer.valueOf(MarketingSendAttemptStatus.WAITING.code()).equals(attempt.getStatus())) {
            int updated = taskMapper.markWaitingAttemptFailed(
                    attempt.getId(), reasonCode, reasonMessage, resultAt);
            if (updated == 0) {
                return false;
            }
            taskMapper.markTargetFailedFromAttempt(
                    attempt.getTargetId(), attempt.getId(), reasonCode, reasonMessage, resultAt);
            return true;
        }
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

    private void submitWaitingAttemptIfNeeded(MarketingTaskSendAttempt attempt,
                                              String commandId,
                                              long submittedAt) {
        if (!Integer.valueOf(MarketingSendAttemptStatus.WAITING.code()).equals(attempt.getStatus())) {
            recordOutboxAcceptedIfNeeded(attempt, commandId, submittedAt);
            return;
        }
        int updated = taskMapper.markWaitingAttemptSubmitted(attempt.getId(), commandId, submittedAt);
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "新群等待记录状态已变化，请重试");
        }
    }

    private void recordOutboxAcceptedIfNeeded(MarketingTaskSendAttempt attempt,
                                               String commandId,
                                               long acceptedAt) {
        if (attempt.getOutboxAcceptedAt() != null) {
            return;
        }
        int updated = taskMapper.markAttemptOutboxAccepted(attempt.getId(), commandId, acceptedAt);
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "营销发送记录状态已变化，请重试");
        }
        attempt.setOutboxAcceptedAt(acceptedAt);
    }

    private List<MarketingTaskSendAttempt> skipOrdinaryCovered(List<MarketingTaskSendAttempt> waiting,
                                                               long resultAt) {
        List<MarketingTaskSendAttempt> uncovered = new ArrayList<>();
        for (MarketingTaskSendAttempt attempt : waiting) {
            if (taskMapper.countOrdinarySubmittedOrSuccessfulAttempts(
                    attempt.getTargetId(), attempt.getGroupJid()) > 0) {
                markSkipped(attempt, REASON_ORDINARY_ROUND_COVERED, "已被普通轮次覆盖", resultAt);
            } else {
                uncovered.add(attempt);
            }
        }
        return uncovered;
    }

    private ClaimedImmediateTargets dueSendableTargets(MarketingTaskTarget target,
                                                       List<MarketingTaskSendAttempt> waiting,
                                                       long resultAt) {
        List<MarketingResolvedTarget> targets = new ArrayList<>();
        List<MarketingTaskSendAttempt> attempts = new ArrayList<>();
        for (MarketingTaskSendAttempt attempt : waiting) {
            MarketingTargetCandidateRow group = attempt.getGroupLinkId() == null
                    ? taskMapper.selectCurrentTargetGroupByJid(
                            target.getAccountId(), normalizeGroupJid(attempt.getGroupJid()))
                    : taskMapper.selectCurrentTargetGroup(target.getAccountId(), attempt.getGroupLinkId());
            if (group == null || !normalizeGroupJid(attempt.getGroupJid()).equals(
                    normalizeGroupJid(group.getGroupJid()))) {
                markSkipped(attempt, REASON_GROUP_NOT_SENDABLE, "账号当前不再具备该群发送条件", resultAt);
                continue;
            }
            targets.add(new MarketingResolvedTarget(
                    target, group.getGroupLinkId(), group.getGroupJid(), group.getGroupName()));
            attempts.add(attempt);
        }
        return new ClaimedImmediateTargets(targets, attempts);
    }

    private void skipAttempts(List<MarketingTaskSendAttempt> attempts,
                              String reasonCode,
                              String reasonMessage,
                              long resultAt) {
        for (MarketingTaskSendAttempt attempt : attempts) {
            markSkipped(attempt, reasonCode, reasonMessage, resultAt);
        }
    }

    private void markSkipped(MarketingTaskSendAttempt attempt,
                             String reasonCode,
                             String reasonMessage,
                             long resultAt) {
        taskMapper.markWaitingAttemptSkipped(attempt.getId(), reasonCode, reasonMessage, resultAt);
    }

    private static boolean validDynamicTarget(MarketingTask task,
                                              MarketingTaskTarget target,
                                              List<MarketingTaskSendAttempt> waiting) {
        return Integer.valueOf(MarketingBusinessType.ORDINARY.code()).equals(task.getBusinessType())
                && delayEnabled(task)
                && target != null
                && Integer.valueOf(MarketingTargetScope.ACCOUNT_DYNAMIC.code()).equals(target.getTargetScope())
                && waiting.stream().allMatch(attempt -> task.getId().equals(attempt.getMarketingTaskId())
                        && target.getId().equals(attempt.getTargetId()));
    }

    private static boolean delayEnabled(MarketingTask task) {
        return Boolean.TRUE.equals(task.getNewGroupDelayEnabled());
    }

    private static long delayMilliseconds(MarketingTask task) {
        MarketingNewGroupDelayUnit unit = MarketingNewGroupDelayUnit.fromCode(task.getNewGroupDelayUnit());
        Integer value = task.getNewGroupDelayValue();
        if (value == null || !unit.supports(value)) {
            throw new BusinessException(ErrorCode.VALIDATION, "新群延迟配置无效");
        }
        return unit.toMilliseconds(value);
    }

    private static String normalizeGroupJid(String groupJid) {
        return groupJid == null ? "" : groupJid.trim();
    }

    /**
     * 清理无效新群并按群 JID 保留首次出现记录。
     *
     * @param groups 原始新群列表，可空
     * @return 不可变的规范化去重列表
     */
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

    /**
     * 判断任务在指定时刻是否仍处于可发送时间窗。
     *
     * @param task 营销任务，可空
     * @param now 判断时间（epoch 毫秒）
     * @return 任务处于发送中且未超出时间窗时返回 {@code true}
     */
    private static boolean isSendingNow(MarketingTask task, long now) {
        return task != null
                && Integer.valueOf(MarketingTaskStatus.SENDING.code()).equals(task.getStatus())
                && (task.getTaskStartAt() == null || task.getTaskStartAt() <= now)
                && (task.getTaskEndAt() == null || task.getTaskEndAt() > now);
    }

    /**
     * 判断新群事件是否可以登记首次发送。
     *
     * <p>发送中任务沿用即时或延迟分支；暂停任务只有开启延迟时才允许创建 WAITING，
     * 且绝不在本入口写 Outbox。任务时间窗在暂停期间仍继续流逝。</p>
     *
     * @param task 当前账号占用的普通营销任务
     * @param now Armada 确认新增群的时间(epoch 毫秒)
     * @return 可以登记首次发送时返回 {@code true}
     */
    private static boolean canRegisterNewGroup(MarketingTask task, long now) {
        if (task == null
                || task.getTaskStartAt() != null && task.getTaskStartAt() > now
                || task.getTaskEndAt() != null && task.getTaskEndAt() <= now) {
            return false;
        }
        if (Integer.valueOf(MarketingTaskStatus.SENDING.code()).equals(task.getStatus())) {
            return true;
        }
        return Integer.valueOf(MarketingTaskStatus.PAUSED.code()).equals(task.getStatus())
                && delayEnabled(task);
    }

    /**
     * 判断固定目标是否属于当前拉群任务且具备首发基本条件。
     *
     * @param task 拉群营销公共任务
     * @param target 固定群目标
     * @param marketingTaskId 调用方指定的任务 ID
     * @param now 判断时间（epoch 毫秒）
     * @return 任务、目标类型、归属和群 JID 均有效时返回 {@code true}
     */
    private static boolean isSendableGroupPullTarget(
            MarketingTask task,
            MarketingTaskTarget target,
            Long marketingTaskId,
            long now) {
        return isSendingNow(task, now)
                && Integer.valueOf(MarketingBusinessType.GROUP_PULL.code())
                        .equals(task.getBusinessType())
                && target != null
                && marketingTaskId.equals(target.getMarketingTaskId())
                && Integer.valueOf(MarketingTargetScope.GROUP_FIXED.code())
                        .equals(target.getTargetScope())
                && StringUtils.hasText(target.getGroupJid());
    }

    /** 已通过唯一键抢占的实际群和对应 attempt，两个列表顺序严格一致。 */
    private record ClaimedImmediateTargets(
            List<MarketingResolvedTarget> targets,
            List<MarketingTaskSendAttempt> attempts) {
    }
}
