package com.armada.marketing.service.impl;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.dto.MarketingNewGroupDTO;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.model.entity.MarketingTaskTarget;
import com.armada.marketing.model.enums.MarketingSendAttemptStatus;
import com.armada.marketing.model.enums.MarketingBusinessType;
import com.armada.marketing.model.enums.MarketingTaskStatus;
import com.armada.marketing.model.enums.MarketingTargetScope;
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

    /** 安全日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(MarketingNewGroupImmediateSendServiceImpl.class);

    /** 新群首次即时发送使用的保留轮次号。 */
    private static final long IMMEDIATE_ROUND_NO = 0L;

    /** 新群首次即时发送的初始尝试次数。 */
    private static final int INITIAL_ATTEMPT_NO = 1;

    /** 模板配置无法生成消息时写入发送明细的稳定失败码。 */
    private static final String REASON_INVALID_TEMPLATE_CONFIG = "INVALID_TEMPLATE_CONFIG";

    /** 营销任务、目标和发送尝试数据访问。 */
    private final MarketingTaskMapper taskMapper;

    /** 营销消息内容及协议命令组装器。 */
    private final MarketingMessageCommandFactory messageFactory;

    /** 统一消息发送端口，负责持久化协议 outbox。 */
    private final MessageSendPort messageSendPort;

    /** 复用普通营销 outbox 分批参数。 */
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
        attempt.setSubmittedAt(detectedAt);
        attempt.setAttemptedAt(detectedAt);
        attempt.setCreatedAt(detectedAt);
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
            if (!item.accepted() && finalizeLocalFailure(
                    attempts.get(index), item.reasonCode(), item.reasonMessage(), detectedAt)) {
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
