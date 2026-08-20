package com.armada.marketing.scheduler;

import com.armada.group.model.enums.AccountGroupMembershipStatus;
import com.armada.group.model.vo.AccountGroupMembershipLookup;
import com.armada.group.model.vo.AccountGroupMembershipStatusSnapshot;
import com.armada.group.service.AccountGroupMembershipStatusService;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.model.entity.MarketingTaskTarget;
import com.armada.marketing.model.enums.MarketingSendAttemptStatus;
import com.armada.marketing.model.enums.MarketingBusinessType;
import com.armada.marketing.model.enums.MarketingTaskStatus;
import com.armada.marketing.model.enums.MarketingTargetScope;
import com.armada.marketing.model.support.MarketingResolvedTarget;
import com.armada.marketing.model.support.MarketingSendAttemptResult;
import com.armada.marketing.model.vo.MarketingAccountOccupancyOwnerRow;
import com.armada.marketing.model.vo.MarketingTargetCandidateRow;
import com.armada.marketing.service.MarketingMessageCommandFactory;
import com.armada.marketing.service.MarketingMessageComposer;
import com.armada.marketing.service.impl.MarketingAccountOccupancyService;
import com.armada.marketing.service.impl.MarketingMembershipSendPolicy;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.port.MessageSendPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 单个营销任务的一轮发送生成器。
 *
 * <p>每轮会对任务下全部目标各生成一条 {@code marketing_task_send_attempt},
 * 再按批写入 {@code message.send.requested} 协议 outbox 命令。真实 WhatsApp 发送发生在协议层,
 * 不在 API 事务内同步执行。</p>
 */
@Component
@Profile("kafka")
public class MarketingRoundWorker {
    private static final Logger log = LoggerFactory.getLogger(MarketingRoundWorker.class);
    private static final String REASON_INVALID_TEMPLATE_CONFIG = "INVALID_TEMPLATE_CONFIG";
    private static final String REASON_ACCOUNT_OCCUPIED = "ACCOUNT_OCCUPIED";

    private final MarketingTaskMapper taskMapper;
    private final MarketingAccountOccupancyService occupancyService;
    private final AccountGroupMembershipStatusService membershipStatusService;
    private final MarketingMessageCommandFactory messageFactory;
    private final MessageSendPort messageSendPort;
    private final MarketingRoundSchedulerProperties properties;
    private final Clock clock;

    /**
     * 注入营销任务轮次所需的数据访问、消息组装、outbox、调度配置和系统时钟。
     */
    public MarketingRoundWorker(MarketingTaskMapper taskMapper,
                                MarketingAccountOccupancyService occupancyService,
                                AccountGroupMembershipStatusService membershipStatusService,
                                MarketingMessageCommandFactory messageFactory,
                                MessageSendPort messageSendPort,
                                MarketingRoundSchedulerProperties properties,
                                Clock clock) {
        this.taskMapper = taskMapper;
        this.occupancyService = occupancyService;
        this.membershipStatusService = membershipStatusService;
        this.messageFactory = messageFactory;
        this.messageSendPort = messageSendPort;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 生成一个任务的一轮发送命令。
     *
     * <p>调度器在无请求上下文的后台线程中调用这里,所以必须显式设置 TenantContext,
     * 让 MyBatis 租户拦截器把所有读写限制在当前租户内。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void runRound(Long tenantId, Long taskId) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            doRunRound(taskId);
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    /**
     * 在已设置租户上下文后执行一轮营销发送生成。
     *
     * <p>这里按固定顺序完成任务状态校验、目标解析、积压保护、轮次抢占、attempt 入库和 outbox 写入。
     * 先解析目标再抢占轮次,是为了账号动态维度没有可发送群时只推迟下一轮,不空耗一个轮次号。</p>
     */
    private void doRunRound(Long taskId) {
        MarketingTask task = taskMapper.selectTaskById(taskId);
        if (task == null) {
            log.warn("营销任务轮次跳过:任务不存在 taskId={}", taskId);
            return;
        }
        if (!Integer.valueOf(MarketingTaskStatus.SENDING.code()).equals(task.getStatus())) {
            log.debug("营销任务轮次跳过:任务非发送中 tenantId={} taskId={} status={}",
                    task.getTenantId(), task.getId(), task.getStatus());
            return;
        }
        long now = clock.millis();
        if (endExpiredTaskIfNeeded(task, now)) {
            return;
        }
        // 即使历史数据或并发操作错误地提前置为发送中,worker 也不能越过计划开始时间生成消息。
        if (task.getTaskStartAt() != null && task.getTaskStartAt() > now) {
            int deferred = taskMapper.deferEarlySendingTask(taskId, now);
            log.warn("营销任务轮次跳过并退回等待:尚未到计划开始时间 tenantId={} taskId={} taskStartAt={} "
                            + "updated={} accountsRetained=true",
                    task.getTenantId(), task.getId(), task.getTaskStartAt(), deferred);
            return;
        }
        List<MarketingTaskTarget> targets = taskMapper.selectTargetsByTaskId(taskId);
        if (targets.isEmpty()) {
            log.warn("营销任务轮次跳过:没有目标 tenantId={} taskId={}", task.getTenantId(), task.getId());
            return;
        }
        if (isGroupPull(task)) {
            Set<Long> sendableTargetIds = Set.copyOf(
                    taskMapper.selectSendableGroupPullTargetIds(taskId));
            targets = targets.stream()
                    .filter(target -> sendableTargetIds.contains(target.getId()))
                    .toList();
        }
        List<MarketingResolvedTarget> resolvedTargets = resolveSendTargets(task, targets);
        if (resolvedTargets.isEmpty()) {
            now = clock.millis();
            if (endExpiredTaskIfNeeded(task, now)) {
                return;
            }
            long nextRoundAt = now + sendIntervalSeconds(task) * 1000L;
            taskMapper.postponeDueRound(taskId, now, nextRoundAt);
            log.info("营销任务轮次推迟:没有解析到本轮可发送群 tenantId={} taskId={} sourceTargetCount={} nextRoundAt={}",
                    task.getTenantId(), task.getId(), targets.size(), nextRoundAt);
            return;
        }
        Map<MembershipKey, AccountGroupMembershipStatus> membershipStatuses =
                loadMembershipStatuses(resolvedTargets);
        Map<Long, MarketingAccountOccupancyOwnerRow> owners = isGroupPull(task)
                ? Map.of()
                : occupancyService.acquireAndLoadTaskAccounts(task, now);
        TargetPartition partition = partitionTargets(
                task, resolvedTargets, owners, membershipStatuses, isGroupPull(task));
        List<MarketingResolvedTarget> sendTargets = partition.sendableTargets();
        long unfinished = sendTargets.isEmpty() ? 0L : taskMapper.countUnfinishedAttempts(taskId);
        // 目标解析和积压查询可能跨过结束时间;抢占轮次前必须使用新时间再次关闸。
        now = clock.millis();
        if (endExpiredTaskIfNeeded(task, now)) {
            return;
        }
        long nextRoundAt = now + sendIntervalSeconds(task) * 1000L;
        long backlogThreshold = (long) Math.max(1, properties.getBacklogMultiplier()) * sendTargets.size();
        // 下游协议层积压过高时只推迟下一轮,避免持续生成新 attempt 把 outbox 堆穿。
        if (!sendTargets.isEmpty() && unfinished >= backlogThreshold) {
            taskMapper.postponeDueRound(taskId, now, nextRoundAt);
            log.info("营销任务轮次因积压推迟 tenantId={} taskId={} targetCount={} unfinished={} "
                            + "backlogThreshold={} nextRoundAt={}",
                    task.getTenantId(), task.getId(), sendTargets.size(), unfinished, backlogThreshold, nextRoundAt);
            return;
        }
        // claimDueRound 是并发闸门:只有一个线程能把到期任务推进到下一轮。
        int claimed = taskMapper.claimDueRound(taskId, now, nextRoundAt);
        if (claimed == 0) {
            log.debug("营销任务轮次抢占失败 tenantId={} taskId={}", task.getTenantId(), task.getId());
            return;
        }

        long roundNo = task.getCurrentRoundNo() == null ? 1L : task.getCurrentRoundNo() + 1L;
        executeClaimedRound(task, targets.size(), partition, roundNo, now, nextRoundAt);
    }

    private void executeClaimedRound(MarketingTask task,
                                     int sourceTargetCount,
                                     TargetPartition partition,
                                     long roundNo,
                                     long now,
                                     long nextRoundAt) {
        List<MarketingTaskSendAttempt> skippedAttempts = new ArrayList<>();
        skippedAttempts.addAll(membershipSkippedAttempts(
                task, partition.membershipSkippedTargets(), roundNo, now));
        skippedAttempts.addAll(occupiedAttempts(task, partition.occupiedTargets(), roundNo, now));
        List<MarketingResolvedTarget> sendTargets = partition.sendableTargets();
        if (sendTargets.isEmpty()) {
            insertAttempts(skippedAttempts, "营销业务跳过尝试");
            log.info("营销任务本轮目标均跳过 tenantId={} taskId={} roundNo={} membershipSkipped={} "
                            + "occupiedSkipped={} nextRoundAt={}",
                    task.getTenantId(), task.getId(), roundNo, partition.membershipSkippedTargets().size(),
                    partition.occupiedTargets().size(), nextRoundAt);
            return;
        }

        MarketingMessageComposer.ComposedMessage message;
        try {
            message = messageFactory.composeTaskMessage(task);
        } catch (BusinessException ex) {
            if (ex.getCode() == ErrorCode.NOT_FOUND.code()) {
                throw ex;
            }
            insertAttempts(skippedAttempts, "营销业务跳过尝试");
            recordLocalFailedAttempts(task, sendTargets, partition.membershipStatuses(),
                    roundNo, now, ex.getMessage());
            log.warn("营销任务模板配置错误,本轮不下发协议命令 tenantId={} taskId={} roundNo={} "
                            + "targetCount={} reasonCode={} reason={}",
                    task.getTenantId(), task.getId(), roundNo, sendTargets.size(),
                    REASON_INVALID_TEMPLATE_CONFIG, ex.getMessage());
            return;
        }

        List<MarketingTaskSendAttempt> attempts = new ArrayList<>(sendTargets.size());
        for (MarketingResolvedTarget sendTarget : sendTargets) {
            attempts.add(toAttempt(task, sendTarget, partition.statusOf(sendTarget), roundNo, now));
        }
        List<MarketingTaskSendAttempt> allAttempts = new ArrayList<>(skippedAttempts.size() + attempts.size());
        allAttempts.addAll(skippedAttempts);
        allAttempts.addAll(attempts);
        insertAttempts(allAttempts, "营销发送尝试");
        EnqueueSummary summary = enqueueCommands(task, sendTargets, attempts, message, now);
        log.info("营销任务轮次发送命令已生成 tenantId={} taskId={} roundNo={} targetCount={} "
                        + "membershipSkipped={} occupiedSkipped={} sourceTargetCount={} attemptCount={} commandCount={} "
                        + "outboxBatches={} batchSize={} messageType={} accepted={} rejected={} imageBytes={} nextRoundAt={}",
                task.getTenantId(), task.getId(), roundNo, sendTargets.size(),
                partition.membershipSkippedTargets().size(), partition.occupiedTargets().size(), sourceTargetCount,
                attempts.size(), summary.commandCount(), summary.batchCount(), summary.batchSize(),
                message.messageType(), summary.acceptedCount(), summary.rejectedCount(),
                imageBytesLength(message), nextRoundAt);
    }

    /** 到达任务结束时间后幂等归档,调用方必须立即停止本轮后续处理。 */
    private boolean endExpiredTaskIfNeeded(MarketingTask task, long now) {
        if (task.getTaskEndAt() == null || task.getTaskEndAt() > now) {
            return false;
        }
        if (isGroupPull(task)) {
            taskMapper.endExpiredGroupPullTask(task.getId(), now);
            return true;
        }
        int ended = taskMapper.endExpiredTask(task.getId(), now);
        int skipped = ended > 0 ? taskMapper.markTaskWaitingAttemptsSkipped(
                task.getId(), "TASK_EXPIRED", "营销任务已结束", now) : 0;
        int released = ended > 0 ? occupancyService.releaseTaskAccounts(task.getId()) : 0;
        log.info("营销任务轮次跳过并结束:已到任务结束时间 tenantId={} taskId={} taskEndAt={} "
                        + "updated={} skippedWaiting={} releasedAccounts={}",
                task.getTenantId(), task.getId(), task.getTaskEndAt(), ended, skipped, released);
        return true;
    }

    /**
     * 把任务 target 解析成本轮真实要发送的群列表。
     *
     * <p>固定群组 target 使用任务创建时保存的群快照；账号动态 target 会展开成当前账号下的 0 到多条群。
     * 两类目标随后统一批量读取当前关系状态，再决定发送或业务跳过。</p>
     */
    private List<MarketingResolvedTarget> resolveSendTargets(
            MarketingTask task,
            List<MarketingTaskTarget> targets) {
        List<MarketingResolvedTarget> sendTargets = new ArrayList<>();
        for (MarketingTaskTarget target : targets) {
            if (isAccountDynamicTarget(target)) {
                appendAccountDynamicSendTargets(task, target, sendTargets);
            } else {
                appendFixedGroupSendTarget(target, sendTargets);
            }
        }
        return sendTargets;
    }

    /**
     * 追加固定群组维度的本轮发送目标。
     *
     * <p>固定群组语义是“用户明确选择哪些群”，所以先使用任务创建时保存的群快照解析真实目标；
     * 解析完成后仍会在统一发送边界读取当前 membership，退出关系只写跳过明细。</p>
     */
    private void appendFixedGroupSendTarget(
            MarketingTaskTarget target,
            List<MarketingResolvedTarget> sendTargets) {
        if (!StringUtils.hasText(target.getGroupJid())) {
            log.warn("营销固定群目标缺少groupJid,本轮跳过 tenantId={} taskId={} targetId={} accountId={}",
                    target.getTenantId(), target.getMarketingTaskId(), target.getId(), target.getAccountId());
            return;
        }
        sendTargets.add(new MarketingResolvedTarget(target, target.getGroupLinkId(),
                target.getGroupJid(), target.getGroupName()));
    }

    /**
     * 追加账号动态维度的本轮发送目标。
     *
     * <p>动态维度每轮从账号当前在群关系里取符合发送时间边界的群。
     * 任务运行期间新增或退出的群,在 membership 全量同步后的下一轮自然生效。</p>
     */
    private void appendAccountDynamicSendTargets(MarketingTask task,
                                                 MarketingTaskTarget target,
                                                 List<MarketingResolvedTarget> sendTargets) {
        List<MarketingTargetCandidateRow> groups = taskMapper.selectDynamicTargetGroups(
                target.getId(), target.getAccountId(), task.getAccountGroupSendAt(), task.getNextRoundAt());
        if (groups.isEmpty()) {
            log.debug("营销账号动态目标本轮无可发送群 tenantId={} taskId={} targetId={} accountId={}",
                    target.getTenantId(), target.getMarketingTaskId(), target.getId(), target.getAccountId());
            return;
        }
        for (MarketingTargetCandidateRow group : groups) {
            if (!StringUtils.hasText(group.getGroupJid())) {
                continue;
            }
            sendTargets.add(new MarketingResolvedTarget(target, group.getGroupLinkId(),
                    group.getGroupJid(), group.getGroupName()));
        }
    }

    /** 判断 target 是否按账号动态维度发送;历史数据 targetScope 为空时默认走固定群组兼容路径。 */
    private static boolean isAccountDynamicTarget(MarketingTaskTarget target) {
        return Integer.valueOf(MarketingTargetScope.ACCOUNT_DYNAMIC.code()).equals(target.getTargetScope());
    }

    /** 按账号当前租约把本轮实际群目标拆成可发送和占用跳过两部分。 */
    private static TargetPartition partitionTargets(
            MarketingTask task,
            List<MarketingResolvedTarget> resolvedTargets,
            Map<Long, MarketingAccountOccupancyOwnerRow> owners,
            Map<MembershipKey, AccountGroupMembershipStatus> membershipStatuses,
            boolean groupPull) {
        List<MarketingResolvedTarget> sendable = new ArrayList<>();
        List<OccupiedMarketingTarget> occupied = new ArrayList<>();
        List<MembershipSkippedTarget> membershipSkipped = new ArrayList<>();
        for (MarketingResolvedTarget target : resolvedTargets) {
            AccountGroupMembershipStatus status = membershipStatuses.getOrDefault(
                    membershipKey(target), AccountGroupMembershipStatus.UNCONFIRMED);
            MarketingMembershipSendPolicy.Decision decision = MarketingMembershipSendPolicy.decide(status);
            if (!decision.sendable()) {
                membershipSkipped.add(new MembershipSkippedTarget(target, status, decision));
                continue;
            }
            if (groupPull) {
                sendable.add(target);
                continue;
            }
            MarketingAccountOccupancyOwnerRow owner = owners.get(target.target().getAccountId());
            if (owner != null && task.getId().equals(owner.getMarketingTaskId())) {
                sendable.add(target);
            } else {
                occupied.add(new OccupiedMarketingTarget(target, owner, status));
            }
        }
        return new TargetPartition(sendable, occupied, membershipSkipped, membershipStatuses);
    }

    private static boolean isGroupPull(MarketingTask task) {
        return task != null
                && Integer.valueOf(MarketingBusinessType.GROUP_PULL.code())
                        .equals(task.getBusinessType());
    }

    /** 一次批量读取本轮所有真实账号+群目标的当前关系状态。 */
    private Map<MembershipKey, AccountGroupMembershipStatus> loadMembershipStatuses(
            List<MarketingResolvedTarget> targets) {
        List<AccountGroupMembershipLookup> lookups = targets.stream()
                .map(target -> new AccountGroupMembershipLookup(
                        target.target().getAccountId(), target.groupJid()))
                .distinct()
                .toList();
        List<AccountGroupMembershipStatusSnapshot> snapshots =
                membershipStatusService.findCurrentStatuses(lookups);
        Map<MembershipKey, AccountGroupMembershipStatus> statuses = new HashMap<>();
        for (AccountGroupMembershipStatusSnapshot snapshot : snapshots) {
            statuses.put(new MembershipKey(snapshot.accountId(), normalizeGroupJid(snapshot.groupJid())),
                    snapshot.status());
        }
        return statuses;
    }

    private static MembershipKey membershipKey(MarketingResolvedTarget target) {
        return new MembershipKey(target.target().getAccountId(), normalizeGroupJid(target.groupJid()));
    }

    private static String normalizeGroupJid(String groupJid) {
        return groupJid == null ? null : groupJid.trim();
    }

    /** 为当前关系不可发送的真实群目标生成业务跳过明细。 */
    private List<MarketingTaskSendAttempt> membershipSkippedAttempts(
            MarketingTask task,
            List<MembershipSkippedTarget> skippedTargets,
            long roundNo,
            long now) {
        List<MarketingTaskSendAttempt> attempts = new ArrayList<>(skippedTargets.size());
        for (MembershipSkippedTarget skippedTarget : skippedTargets) {
            MarketingTaskSendAttempt attempt = toAttempt(
                    task, skippedTarget.target(), skippedTarget.status(), roundNo, now);
            attempt.setCommandId(null);
            attempt.setStatus(MarketingSendAttemptStatus.SKIPPED.code());
            attempt.setReasonCode(skippedTarget.decision().reasonCode());
            attempt.setReasonMessage(skippedTarget.decision().reasonMessage());
            attempt.setGroupStatusReason(skippedTarget.decision().reasonCode());
            attempt.setSubmittedAt(null);
            attempt.setResultAt(now);
            attempts.add(attempt);
        }
        return attempts;
    }

    /** 为本轮仍被其它任务占用的每条实际群消息生成业务跳过明细。 */
    private List<MarketingTaskSendAttempt> occupiedAttempts(
            MarketingTask task,
            List<OccupiedMarketingTarget> occupiedTargets,
            long roundNo,
            long now) {
        List<MarketingTaskSendAttempt> attempts = new ArrayList<>(occupiedTargets.size());
        for (OccupiedMarketingTarget occupiedTarget : occupiedTargets) {
            MarketingTaskSendAttempt attempt = toAttempt(
                    task, occupiedTarget.target(), occupiedTarget.status(), roundNo, now);
            attempt.setCommandId(null);
            attempt.setStatus(MarketingSendAttemptStatus.SKIPPED.code());
            attempt.setReasonCode(REASON_ACCOUNT_OCCUPIED);
            attempt.setReasonMessage(occupancyService.occupiedAttemptMessage(occupiedTarget.owner()));
            attempt.setSubmittedAt(null);
            attempt.setResultAt(now);
            attempts.add(attempt);
        }
        return attempts;
    }

    /** 批量写入本轮尝试并严格核对行数；空列表不触库。 */
    private void insertAttempts(List<MarketingTaskSendAttempt> attempts, String operation) {
        if (attempts.isEmpty()) {
            return;
        }
        int inserted = taskMapper.insertSendAttempts(attempts);
        if (inserted != attempts.size()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    operation + "写入数量不一致: expected=" + attempts.size() + ", inserted=" + inserted);
        }
    }

    /**
     * 构造一条已提交状态的发送 attempt。
     *
     * <p>attempt 保存本轮实际群快照,这是账号动态目标“一条 target 多个群”的审计依据;
     * commandId 在这里预生成,随后带入协议 outbox 建立一一对应关系。</p>
     */
    private MarketingTaskSendAttempt toAttempt(MarketingTask task,
                                               MarketingResolvedTarget sendTarget,
                                               AccountGroupMembershipStatus membershipStatus,
                                               long roundNo,
                                               long now) {
        MarketingTaskSendAttempt attempt = new MarketingTaskSendAttempt();
        attempt.setMarketingTaskId(task.getId());
        attempt.setTargetId(sendTarget.target().getId());
        attempt.setGroupLinkId(sendTarget.groupLinkId());
        attempt.setGroupJid(sendTarget.groupJid());
        attempt.setGroupName(sendTarget.groupName());
        attempt.setRoundNo(roundNo);
        attempt.setAttemptNo(1);
        attempt.setRetry(false);
        attempt.setCommandId(messageFactory.newCommandId());
        attempt.setStatus(MarketingSendAttemptStatus.SUBMITTED.code());
        AccountGroupMembershipStatus resolvedStatus = membershipStatus == null
                ? AccountGroupMembershipStatus.UNCONFIRMED : membershipStatus;
        attempt.setGroupStatus(resolvedStatus.apiValue());
        attempt.setGroupStatusCheckedAt(now);
        attempt.setSubmittedAt(now);
        attempt.setAttemptedAt(now);
        attempt.setCreatedAt(now);
        return attempt;
    }

    /**
     * 历史脏数据可能绕过保存期校验。这里本地记录失败,避免继续下发“看似成功但按钮丢失”的纯文本消息。
     */
    private void recordLocalFailedAttempts(MarketingTask task,
                                           List<MarketingResolvedTarget> sendTargets,
                                           Map<MembershipKey, AccountGroupMembershipStatus> membershipStatuses,
                                           long roundNo,
                                           long now,
                                           String reasonMessage) {
        List<MarketingTaskSendAttempt> attempts = new ArrayList<>(sendTargets.size());
        for (MarketingResolvedTarget sendTarget : sendTargets) {
            MarketingTaskSendAttempt attempt = toAttempt(
                    task,
                    sendTarget,
                    membershipStatuses.getOrDefault(
                            membershipKey(sendTarget), AccountGroupMembershipStatus.UNCONFIRMED),
                    roundNo,
                    now);
            attempt.setStatus(MarketingSendAttemptStatus.FAILED.code());
            attempt.setReasonCode(REASON_INVALID_TEMPLATE_CONFIG);
            attempt.setReasonMessage(reasonMessage);
            attempt.setResultAt(now);
            attempts.add(attempt);
        }
        int inserted = taskMapper.insertSendAttempts(attempts);
        if (inserted != attempts.size()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "营销发送失败尝试写入数量不一致: expected=" + attempts.size() + ", inserted=" + inserted);
        }
        taskMapper.incrementTaskSendCounters(task.getId(), 0, attempts.size(), now);
        for (MarketingTaskSendAttempt attempt : attempts) {
            taskMapper.markTargetFailedFromAttempt(attempt.getTargetId(), attempt.getId(),
                    REASON_INVALID_TEMPLATE_CONFIG, reasonMessage, now);
        }
    }

    /**
     * 按 outbox 批大小写协议命令。
     *
     * <p>attempt 已经带有预生成 commandId,这里把同一个 commandId 带到协议 outbox,
     * 保证 attempt 与协议命令可以一一对应。</p>
     */
    private EnqueueSummary enqueueCommands(MarketingTask task,
                                           List<MarketingResolvedTarget> sendTargets,
                                           List<MarketingTaskSendAttempt> attempts,
                                           MarketingMessageComposer.ComposedMessage message,
                                           long roundStartedAt) {
        int batchSize = outboxBatchSize(message);
        int dispatchIntervalMs = messageFactory.accountGroupSendIntervalMs(task);
        Map<Long, Integer> accountPositions = new HashMap<>();
        List<MessageSendCommand> batch = new ArrayList<>(batchSize);
        List<MarketingTaskSendAttempt> batchAttempts = new ArrayList<>(batchSize);
        int batchCount = 0;
        int commandCount = 0;
        int acceptedCount = 0;
        int rejectedCount = 0;
        long resultAt = clock.millis();
        for (int i = 0; i < attempts.size(); i++) {
            MarketingResolvedTarget sendTarget = sendTargets.get(i);
            MarketingTaskTarget target = sendTarget.target();
            MarketingTaskSendAttempt attempt = attempts.get(i);
            int accountPosition = accountPositions.getOrDefault(target.getAccountId(), 0);
            accountPositions.put(target.getAccountId(), accountPosition + 1);
            long notBeforeAt = roundStartedAt + (long) accountPosition * dispatchIntervalMs;
            batch.add(messageFactory.toCommand(task, sendTarget, attempt, message, notBeforeAt));
            batchAttempts.add(attempt);
            if (batch.size() == batchSize) {
                BatchResult result = enqueueBatch(batch, batchAttempts, resultAt);
                batchCount++;
                commandCount += batch.size();
                acceptedCount += result.acceptedCount();
                rejectedCount += result.rejectedCount();
                batch = new ArrayList<>(batchSize);
                batchAttempts = new ArrayList<>(batchSize);
            }
        }
        if (!batch.isEmpty()) {
            BatchResult result = enqueueBatch(batch, batchAttempts, resultAt);
            batchCount++;
            commandCount += batch.size();
            acceptedCount += result.acceptedCount();
            rejectedCount += result.rejectedCount();
        }
        if (rejectedCount > 0) {
            taskMapper.incrementTaskSendCounters(task.getId(), 0, rejectedCount, resultAt);
        }
        return new EnqueueSummary(batchSize, batchCount, commandCount, acceptedCount, rejectedCount);
    }

    /** 把一批协议无关命令交给 routing port，并把本地拒绝收敛到 attempt/target。 */
    private BatchResult enqueueBatch(List<MessageSendCommand> commands,
                                     List<MarketingTaskSendAttempt> attempts,
                                     long resultAt) {
        MessageSendEnqueueResult result = messageSendPort.enqueue(commands);
        if (result == null || result.items().size() != commands.size() || attempts.size() != commands.size()) {
            throw new IllegalStateException("营销消息入队结果数量与命令不一致");
        }
        int accepted = 0;
        int rejected = 0;
        for (int i = 0; i < commands.size(); i++) {
            MessageSendCommand command = commands.get(i);
            MessageSendEnqueueItem item = result.items().get(i);
            MarketingTaskSendAttempt attempt = attempts.get(i);
            if (item == null || !command.commandId().equals(item.commandId())) {
                throw new IllegalStateException("营销消息入队结果 commandId 与命令不一致");
            }
            if (item.accepted()) {
                int updated = taskMapper.markAttemptOutboxAccepted(
                        attempt.getId(), command.commandId(), resultAt);
                if (updated != 1) {
                    throw new IllegalStateException("营销发送记录 Outbox 接受状态更新失败");
                }
                attempt.setOutboxAcceptedAt(resultAt);
                accepted++;
                continue;
            }
            MarketingSendAttemptResult attemptResult = new MarketingSendAttemptResult(
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
            int updated = taskMapper.markAttemptFailed(attemptResult);
            if (updated > 0) {
                taskMapper.markTargetFailedFromAttempt(
                        attempt.getTargetId(),
                        attempt.getId(),
                        item.reasonCode(),
                        item.reasonMessage(),
                        resultAt);
                rejected++;
            }
        }
        return new BatchResult(accepted, rejected);
    }

    /**
     * 计算本轮协议 outbox 批大小。
     *
     * <p>图片消息体更大,默认使用单独配置;最终值限制在 1 到 500,避免错误配置造成空批或超大事务。</p>
     */
    private int outboxBatchSize(MarketingMessageComposer.ComposedMessage message) {
        int configured = messageFactory.hasLargeMediaPayload(message)
                ? properties.getImageOutboxBatchSize()
                : properties.getOutboxBatchSize();
        return Math.max(1, Math.min(500, configured));
    }

    /** 无效间隔兜底为 30 秒,避免后台异常配置导致轮次紧循环。 */
    private static long sendIntervalSeconds(MarketingTask task) {
        Integer configured = task.getSendIntervalSeconds();
        return configured == null || configured < 1 ? 30L : configured.longValue();
    }

    /** 仅用于日志统计图片体大小;文本消息没有图片内容时返回 0。 */
    private static int imageBytesLength(MarketingMessageComposer.ComposedMessage message) {
        return message.imageBytes() == null ? 0 : message.imageBytes().length;
    }

    /** 本轮因账号当前属于其它任务而不能发送的实际群目标。 */
    private record OccupiedMarketingTarget(
            MarketingResolvedTarget target,
            MarketingAccountOccupancyOwnerRow owner,
            AccountGroupMembershipStatus status) {
    }

    /** 本轮因账号群关系不可发送而跳过的真实群目标。 */
    private record MembershipSkippedTarget(
            MarketingResolvedTarget target,
            AccountGroupMembershipStatus status,
            MarketingMembershipSendPolicy.Decision decision) {
    }

    /** 账号 ID 与规范化群 JID 组成的关系状态查询键。 */
    private record MembershipKey(Long accountId, String groupJid) {
    }

    /** 本轮目标按账号租约拆分后的结果。 */
    private record TargetPartition(
            List<MarketingResolvedTarget> sendableTargets,
            List<OccupiedMarketingTarget> occupiedTargets,
            List<MembershipSkippedTarget> membershipSkippedTargets,
            Map<MembershipKey, AccountGroupMembershipStatus> membershipStatuses) {

        private AccountGroupMembershipStatus statusOf(MarketingResolvedTarget target) {
            return membershipStatuses.getOrDefault(
                    membershipKey(target), AccountGroupMembershipStatus.UNCONFIRMED);
        }
    }

    /** 协议 outbox 写入结果摘要,用于轮次日志排查批量拆分是否符合预期。 */
    private record EnqueueSummary(
            int batchSize,
            int batchCount,
            int commandCount,
            int acceptedCount,
            int rejectedCount) {
    }

    /** 单批消息端口处理摘要。 */
    private record BatchResult(int acceptedCount, int rejectedCount) {
    }
}
