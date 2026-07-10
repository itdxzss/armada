package com.armada.marketing.scheduler;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.model.entity.MarketingTaskTarget;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.marketing.model.enums.MarketingSendAttemptStatus;
import com.armada.marketing.model.enums.MarketingTaskStatus;
import com.armada.marketing.model.enums.MarketingTargetScope;
import com.armada.marketing.model.vo.MarketingAccountOccupancyOwnerRow;
import com.armada.marketing.model.vo.MarketingTargetCandidateRow;
import com.armada.marketing.service.MarketingMessageComposer;
import com.armada.marketing.service.impl.MarketingAccountOccupancyService;
import com.armada.platform.protocol.model.command.ProtocolMarketingMessageCommandRequest;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private static final String SOURCE_MARKETING_TASK = "marketing_task";
    private static final String REASON_INVALID_TEMPLATE_CONFIG = "INVALID_TEMPLATE_CONFIG";
    private static final String REASON_ACCOUNT_OCCUPIED = "ACCOUNT_OCCUPIED";

    private final MarketingTaskMapper taskMapper;
    private final MarketingTemplateMapper templateMapper;
    private final MarketingTemplateFileMapper fileMapper;
    private final MarketingAccountOccupancyService occupancyService;
    private final MarketingMessageComposer messageComposer;
    private final ProtocolCommandOutboxService outboxService;
    private final MarketingRoundSchedulerProperties properties;
    private final Clock clock;

    /**
     * 注入营销任务轮次所需的数据访问、消息组装、outbox、调度配置和系统时钟。
     */
    public MarketingRoundWorker(MarketingTaskMapper taskMapper,
                                MarketingTemplateMapper templateMapper,
                                MarketingTemplateFileMapper fileMapper,
                                MarketingAccountOccupancyService occupancyService,
                                MarketingMessageComposer messageComposer,
                                ProtocolCommandOutboxService outboxService,
                                MarketingRoundSchedulerProperties properties,
                                Clock clock) {
        this.taskMapper = taskMapper;
        this.templateMapper = templateMapper;
        this.fileMapper = fileMapper;
        this.occupancyService = occupancyService;
        this.messageComposer = messageComposer;
        this.outboxService = outboxService;
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
            int released = deferred > 0 ? occupancyService.releaseTaskAccounts(taskId) : 0;
            log.warn("营销任务轮次跳过并退回等待:尚未到计划开始时间 tenantId={} taskId={} taskStartAt={} "
                            + "updated={} releasedAccounts={}",
                    task.getTenantId(), task.getId(), task.getTaskStartAt(), deferred, released);
            return;
        }
        List<MarketingTaskTarget> targets = taskMapper.selectTargetsByTaskId(taskId);
        if (targets.isEmpty()) {
            log.warn("营销任务轮次跳过:没有目标 tenantId={} taskId={}", task.getTenantId(), task.getId());
            return;
        }
        List<ResolvedMarketingTarget> resolvedTargets = resolveSendTargets(task, targets);
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
        Map<Long, MarketingAccountOccupancyOwnerRow> owners =
                occupancyService.acquireAndLoadTaskAccounts(task, now);
        TargetPartition partition = partitionTargets(task, resolvedTargets, owners);
        List<ResolvedMarketingTarget> sendTargets = partition.sendableTargets();
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
        List<MarketingTaskSendAttempt> skippedAttempts = occupiedAttempts(task, partition.occupiedTargets(), roundNo, now);
        List<ResolvedMarketingTarget> sendTargets = partition.sendableTargets();
        if (sendTargets.isEmpty()) {
            insertAttempts(skippedAttempts, "营销账号占用跳过尝试");
            log.info("营销任务本轮账号均被占用 tenantId={} taskId={} roundNo={} skipped={} nextRoundAt={}",
                    task.getTenantId(), task.getId(), roundNo, skippedAttempts.size(), nextRoundAt);
            return;
        }

        MarketingTemplate template = requireTemplate(task.getMarketingTemplateId());
        MarketingTemplateFile imageFile = template.getImageFileId() == null
                ? null
                : fileMapper.selectById(template.getImageFileId());
        MarketingMessageComposer.ComposedMessage message;
        try {
            message = messageComposer.compose(template, imageFile);
        } catch (BusinessException ex) {
            insertAttempts(skippedAttempts, "营销账号占用跳过尝试");
            recordLocalFailedAttempts(task, sendTargets, roundNo, now, ex.getMessage());
            log.warn("营销任务模板配置错误,本轮不下发协议命令 tenantId={} taskId={} roundNo={} "
                            + "targetCount={} reasonCode={} reason={}",
                    task.getTenantId(), task.getId(), roundNo, sendTargets.size(),
                    REASON_INVALID_TEMPLATE_CONFIG, ex.getMessage());
            return;
        }

        List<MarketingTaskSendAttempt> attempts = new ArrayList<>(sendTargets.size());
        for (ResolvedMarketingTarget sendTarget : sendTargets) {
            attempts.add(toAttempt(task, sendTarget, roundNo, now));
        }
        List<MarketingTaskSendAttempt> allAttempts = new ArrayList<>(skippedAttempts.size() + attempts.size());
        allAttempts.addAll(skippedAttempts);
        allAttempts.addAll(attempts);
        insertAttempts(allAttempts, "营销发送尝试");
        EnqueueSummary summary = enqueueCommands(task, sendTargets, attempts, message);
        log.info("营销任务轮次发送命令已生成 tenantId={} taskId={} roundNo={} targetCount={} occupiedSkipped={} "
                        + "sourceTargetCount={} attemptCount={} commandCount={} outboxBatches={} batchSize={} messageType={} "
                        + "imageBytes={} nextRoundAt={}",
                task.getTenantId(), task.getId(), roundNo, sendTargets.size(), skippedAttempts.size(), sourceTargetCount,
                attempts.size(), summary.commandCount(), summary.batchCount(), summary.batchSize(),
                message.messageType(), imageBytesLength(message), nextRoundAt);
    }

    /** 到达任务结束时间后幂等归档,调用方必须立即停止本轮后续处理。 */
    private boolean endExpiredTaskIfNeeded(MarketingTask task, long now) {
        if (task.getTaskEndAt() == null || task.getTaskEndAt() > now) {
            return false;
        }
        int ended = taskMapper.endExpiredTask(task.getId(), now);
        int released = ended > 0 ? occupancyService.releaseTaskAccounts(task.getId()) : 0;
        log.info("营销任务轮次跳过并结束:已到任务结束时间 tenantId={} taskId={} taskEndAt={} "
                        + "updated={} releasedAccounts={}",
                task.getTenantId(), task.getId(), task.getTaskEndAt(), ended, released);
        return true;
    }

    /**
     * 把任务 target 解析成本轮真实要发送的群列表。
     *
     * <p>固定群组 target 使用任务创建时保存的群快照;账号动态 target 会展开成当前账号下的 0 到多条群。
     * 后续 attempt 和 outbox 只消费 {@link ResolvedMarketingTarget},避免再关心目标来源维度。</p>
     */
    private List<ResolvedMarketingTarget> resolveSendTargets(MarketingTask task, List<MarketingTaskTarget> targets) {
        List<ResolvedMarketingTarget> sendTargets = new ArrayList<>();
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
     * <p>固定群组语义是“用户明确选择哪些群就发哪些群”,所以发送时直接使用任务创建时保存的群快照,
     * 不再查询账号当前 membership。</p>
     */
    private void appendFixedGroupSendTarget(MarketingTaskTarget target, List<ResolvedMarketingTarget> sendTargets) {
        if (!StringUtils.hasText(target.getGroupJid())) {
            log.warn("营销固定群目标缺少groupJid,本轮跳过 tenantId={} taskId={} targetId={} accountId={}",
                    target.getTenantId(), target.getMarketingTaskId(), target.getId(), target.getAccountId());
            return;
        }
        sendTargets.add(new ResolvedMarketingTarget(target, target.getGroupLinkId(),
                target.getGroupJid(), target.getGroupName()));
    }

    /**
     * 追加账号动态维度的本轮发送目标。
     *
     * <p>动态维度每轮都从账号当前在群关系里取群,并由 SQL 层排除账号导入云控前记录的 baseline 群。
     * 这样任务创建后账号新进群,下一轮就能自然覆盖到。</p>
     */
    private void appendAccountDynamicSendTargets(MarketingTask task,
                                                 MarketingTaskTarget target,
                                                 List<ResolvedMarketingTarget> sendTargets) {
        // 账号动态维度每轮实时查询当前在群关系,SQL 层会排除 baseline 群和本任务发送时间前加入的群。
        List<MarketingTargetCandidateRow> groups = taskMapper.selectDynamicTargetGroups(
                target.getAccountId(), task.getAccountGroupSendAt());
        if (groups.isEmpty()) {
            log.debug("营销账号动态目标本轮无可发送群 tenantId={} taskId={} targetId={} accountId={}",
                    target.getTenantId(), target.getMarketingTaskId(), target.getId(), target.getAccountId());
            return;
        }
        for (MarketingTargetCandidateRow group : groups) {
            if (!StringUtils.hasText(group.getGroupJid())) {
                continue;
            }
            sendTargets.add(new ResolvedMarketingTarget(target, group.getGroupLinkId(),
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
            List<ResolvedMarketingTarget> resolvedTargets,
            Map<Long, MarketingAccountOccupancyOwnerRow> owners) {
        List<ResolvedMarketingTarget> sendable = new ArrayList<>();
        List<OccupiedMarketingTarget> occupied = new ArrayList<>();
        for (ResolvedMarketingTarget target : resolvedTargets) {
            MarketingAccountOccupancyOwnerRow owner = owners.get(target.target().getAccountId());
            if (owner != null && task.getId().equals(owner.getMarketingTaskId())) {
                sendable.add(target);
            } else {
                occupied.add(new OccupiedMarketingTarget(target, owner));
            }
        }
        return new TargetPartition(sendable, occupied);
    }

    /** 为本轮仍被其它任务占用的每条实际群消息生成业务跳过明细。 */
    private List<MarketingTaskSendAttempt> occupiedAttempts(
            MarketingTask task,
            List<OccupiedMarketingTarget> occupiedTargets,
            long roundNo,
            long now) {
        List<MarketingTaskSendAttempt> attempts = new ArrayList<>(occupiedTargets.size());
        for (OccupiedMarketingTarget occupiedTarget : occupiedTargets) {
            MarketingTaskSendAttempt attempt = toAttempt(task, occupiedTarget.target(), roundNo, now);
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

    /** 模板在轮次执行时必须存在;否则本轮应整体回滚并等待人工修正任务配置。 */
    private MarketingTemplate requireTemplate(Long templateId) {
        MarketingTemplate template = templateMapper.selectById(templateId);
        if (template == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "营销模板不存在: " + templateId);
        }
        return template;
    }

    /**
     * 构造一条已提交状态的发送 attempt。
     *
     * <p>attempt 保存本轮实际群快照,这是账号动态目标“一条 target 多个群”的审计依据;
     * commandId 在这里预生成,随后带入协议 outbox 建立一一对应关系。</p>
     */
    private MarketingTaskSendAttempt toAttempt(MarketingTask task,
                                               ResolvedMarketingTarget sendTarget,
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
        attempt.setCommandId(newCommandId());
        attempt.setStatus(MarketingSendAttemptStatus.SUBMITTED.code());
        attempt.setSubmittedAt(now);
        attempt.setAttemptedAt(now);
        attempt.setCreatedAt(now);
        return attempt;
    }

    /**
     * 历史脏数据可能绕过保存期校验。这里本地记录失败,避免继续下发“看似成功但按钮丢失”的纯文本消息。
     */
    private void recordLocalFailedAttempts(MarketingTask task,
                                           List<ResolvedMarketingTarget> sendTargets,
                                           long roundNo,
                                           long now,
                                           String reasonMessage) {
        List<MarketingTaskSendAttempt> attempts = new ArrayList<>(sendTargets.size());
        for (ResolvedMarketingTarget sendTarget : sendTargets) {
            MarketingTaskSendAttempt attempt = toAttempt(task, sendTarget, roundNo, now);
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
                                           List<ResolvedMarketingTarget> sendTargets,
                                           List<MarketingTaskSendAttempt> attempts,
                                           MarketingMessageComposer.ComposedMessage message) {
        int batchSize = outboxBatchSize(message);
        List<ProtocolMarketingMessageCommandRequest> batch = new ArrayList<>(batchSize);
        String imageBase64 = message.imageBytes() == null ? null : Base64.getEncoder().encodeToString(message.imageBytes());
        ProtocolMarketingMessageCommandRequest.MarketingLinkCardPayload linkCard = linkCardPayload(message.linkCard());
        ProtocolMarketingMessageCommandRequest.MarketingButtonCardPayload buttonCard = buttonCardPayload(message.buttonCard());
        int batchCount = 0;
        int commandCount = 0;
        for (int i = 0; i < attempts.size(); i++) {
            ResolvedMarketingTarget sendTarget = sendTargets.get(i);
            MarketingTaskTarget target = sendTarget.target();
            MarketingTaskSendAttempt attempt = attempts.get(i);
            batch.add(new ProtocolMarketingMessageCommandRequest(
                    task.getTenantId(),
                    task.getId(),
                    attempt.getId(),
                    target.getId(),
                    attempt.getRoundNo(),
                    target.getAccountId(),
                    protocolAccountId(target),
                    sendTarget.groupJid(),
                    message.messageType(),
                    message.text(),
                    imageBase64,
                    message.imageMimetype(),
                    linkCard,
                    buttonCard,
                    SOURCE_MARKETING_TASK,
                    attempt.getCommandId()));
            if (batch.size() == batchSize) {
                outboxService.enqueueMarketingMessageCommands(batch);
                batchCount++;
                commandCount += batch.size();
                batch = new ArrayList<>(batchSize);
            }
        }
        if (!batch.isEmpty()) {
            outboxService.enqueueMarketingMessageCommands(batch);
            batchCount++;
            commandCount += batch.size();
        }
        return new EnqueueSummary(batchSize, batchCount, commandCount);
    }

    /**
     * 计算本轮协议 outbox 批大小。
     *
     * <p>图片消息体更大,默认使用单独配置;最终值限制在 1 到 500,避免错误配置造成空批或超大事务。</p>
     */
    private int outboxBatchSize(MarketingMessageComposer.ComposedMessage message) {
        int configured = hasLargeMediaPayload(message)
                ? properties.getImageOutboxBatchSize()
                : properties.getOutboxBatchSize();
        return Math.max(1, Math.min(500, configured));
    }

    private static ProtocolMarketingMessageCommandRequest.MarketingLinkCardPayload linkCardPayload(
            MarketingMessageComposer.LinkCardPayload linkCard) {
        if (linkCard == null) {
            return null;
        }
        return new ProtocolMarketingMessageCommandRequest.MarketingLinkCardPayload(
                linkCard.url(),
                linkCard.title(),
                linkCard.description(),
                mediaPayload(linkCard.thumbnail()));
    }

    private static ProtocolMarketingMessageCommandRequest.MarketingButtonCardPayload buttonCardPayload(
            MarketingMessageComposer.ButtonCardPayload buttonCard) {
        if (buttonCard == null) {
            return null;
        }
        return new ProtocolMarketingMessageCommandRequest.MarketingButtonCardPayload(
                buttonCard.title(),
                buttonCard.footer(),
                buttonCard.buttons().stream()
                        .map(button -> new ProtocolMarketingMessageCommandRequest.MarketingButtonPayload(
                                button.type(), button.displayText(), button.value()))
                        .toList(),
                mediaPayload(buttonCard.thumbnail()));
    }

    private static ProtocolMarketingMessageCommandRequest.MarketingMediaPayload mediaPayload(
            MarketingMessageComposer.MediaPayload media) {
        if (media == null || media.bytes() == null || media.bytes().length == 0) {
            return null;
        }
        return new ProtocolMarketingMessageCommandRequest.MarketingMediaPayload(
                Base64.getEncoder().encodeToString(media.bytes()),
                media.mimetype());
    }

    private static boolean hasLargeMediaPayload(MarketingMessageComposer.ComposedMessage message) {
        return "IMAGE".equals(message.messageType())
                || (message.linkCard() != null && message.linkCard().thumbnail() != null)
                || (message.buttonCard() != null && message.buttonCard().thumbnail() != null);
    }

    /** 优先使用 account.protocol_account_id;测试或历史数据缺失时用账号手机号派生旧协议句柄。 */
    private static String protocolAccountId(MarketingTaskTarget target) {
        if (StringUtils.hasText(target.getProtocolAccountId())) {
            return target.getProtocolAccountId();
        }
        if (StringUtils.hasText(target.getAccountPhone())) {
            return "acc_" + target.getAccountPhone();
        }
        throw new BusinessException(ErrorCode.VALIDATION, "营销目标缺少协议账号ID: targetId=" + target.getId());
    }

    /** 预生成 commandId,让 attempt 行和协议 outbox 行在同一事务里建立可追踪关系。 */
    private static String newCommandId() {
        return "cmd_" + UUID.randomUUID().toString().replace("-", "");
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

    /**
     * 一条库里的 target 在本轮解析出来的实际发送目标。
     *
     * <p>固定群组 target 会解析成一条;账号动态 target 可能解析成 0 到多条。</p>
     */
    private record ResolvedMarketingTarget(
            MarketingTaskTarget target,
            Long groupLinkId,
            String groupJid,
            String groupName) {
    }

    /** 本轮因账号当前属于其它任务而不能发送的实际群目标。 */
    private record OccupiedMarketingTarget(
            ResolvedMarketingTarget target,
            MarketingAccountOccupancyOwnerRow owner) {
    }

    /** 本轮目标按账号租约拆分后的结果。 */
    private record TargetPartition(
            List<ResolvedMarketingTarget> sendableTargets,
            List<OccupiedMarketingTarget> occupiedTargets) {
    }

    /** 协议 outbox 写入结果摘要,用于轮次日志排查批量拆分是否符合预期。 */
    private record EnqueueSummary(int batchSize, int batchCount, int commandCount) {
    }
}
