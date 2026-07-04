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
import com.armada.marketing.service.MarketingMessageComposer;
import com.armada.platform.protocol.model.command.ProtocolMarketingMessageCommandRequest;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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

    private final MarketingTaskMapper taskMapper;
    private final MarketingTemplateMapper templateMapper;
    private final MarketingTemplateFileMapper fileMapper;
    private final MarketingMessageComposer messageComposer;
    private final ProtocolCommandOutboxService outboxService;
    private final MarketingRoundSchedulerProperties properties;

    public MarketingRoundWorker(MarketingTaskMapper taskMapper,
                                MarketingTemplateMapper templateMapper,
                                MarketingTemplateFileMapper fileMapper,
                                MarketingMessageComposer messageComposer,
                                ProtocolCommandOutboxService outboxService,
                                MarketingRoundSchedulerProperties properties) {
        this.taskMapper = taskMapper;
        this.templateMapper = templateMapper;
        this.fileMapper = fileMapper;
        this.messageComposer = messageComposer;
        this.outboxService = outboxService;
        this.properties = properties;
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
        List<MarketingTaskTarget> targets = taskMapper.selectTargetsByTaskId(taskId);
        if (targets.isEmpty()) {
            log.warn("营销任务轮次跳过:没有目标 tenantId={} taskId={}", task.getTenantId(), task.getId());
            return;
        }
        long now = System.currentTimeMillis();
        long nextRoundAt = now + sendIntervalSeconds(task) * 1000L;
        long unfinished = taskMapper.countUnfinishedAttempts(taskId);
        long backlogThreshold = (long) Math.max(1, properties.getBacklogMultiplier()) * targets.size();
        // 下游协议层积压过高时只推迟下一轮,避免持续生成新 attempt 把 outbox 堆穿。
        if (unfinished >= backlogThreshold) {
            taskMapper.postponeDueRound(taskId, now, nextRoundAt);
            log.info("营销任务轮次因积压推迟 tenantId={} taskId={} targetCount={} unfinished={} "
                            + "backlogThreshold={} nextRoundAt={}",
                    task.getTenantId(), task.getId(), targets.size(), unfinished, backlogThreshold, nextRoundAt);
            return;
        }
        // claimDueRound 是并发闸门:只有一个线程能把到期任务推进到下一轮。
        int claimed = taskMapper.claimDueRound(taskId, now, nextRoundAt);
        if (claimed == 0) {
            log.debug("营销任务轮次抢占失败 tenantId={} taskId={}", task.getTenantId(), task.getId());
            return;
        }

        long roundNo = task.getCurrentRoundNo() == null ? 1L : task.getCurrentRoundNo() + 1L;
        MarketingTemplate template = requireTemplate(task.getMarketingTemplateId());
        MarketingTemplateFile imageFile = template.getImageFileId() == null
                ? null
                : fileMapper.selectById(template.getImageFileId());
        MarketingMessageComposer.ComposedMessage message = messageComposer.compose(template, imageFile);

        List<MarketingTaskSendAttempt> attempts = new ArrayList<>(targets.size());
        for (MarketingTaskTarget target : targets) {
            attempts.add(toAttempt(task, target, roundNo, now));
        }
        int inserted = taskMapper.insertSendAttempts(attempts);
        if (inserted != attempts.size()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "营销发送尝试写入数量不一致: expected=" + attempts.size() + ", inserted=" + inserted);
        }
        EnqueueSummary summary = enqueueCommands(task, targets, attempts, message);
        log.info("营销任务轮次发送命令已生成 tenantId={} taskId={} roundNo={} targetCount={} "
                        + "attemptCount={} commandCount={} outboxBatches={} batchSize={} messageType={} "
                        + "imageBytes={} nextRoundAt={}",
                task.getTenantId(), task.getId(), roundNo, targets.size(), attempts.size(),
                summary.commandCount(), summary.batchCount(), summary.batchSize(), message.messageType(),
                imageBytesLength(message), nextRoundAt);
    }

    /** 模板在轮次执行时必须存在;否则本轮应整体回滚并等待人工修正任务配置。 */
    private MarketingTemplate requireTemplate(Long templateId) {
        MarketingTemplate template = templateMapper.selectById(templateId);
        if (template == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "营销模板不存在: " + templateId);
        }
        return template;
    }

    private MarketingTaskSendAttempt toAttempt(MarketingTask task,
                                               MarketingTaskTarget target,
                                               long roundNo,
                                               long now) {
        MarketingTaskSendAttempt attempt = new MarketingTaskSendAttempt();
        attempt.setMarketingTaskId(task.getId());
        attempt.setTargetId(target.getId());
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
     * 按 outbox 批大小写协议命令。
     *
     * <p>attempt 已经带有预生成 commandId,这里把同一个 commandId 带到协议 outbox,
     * 保证 attempt 与协议命令可以一一对应。</p>
     */
    private EnqueueSummary enqueueCommands(MarketingTask task,
                                           List<MarketingTaskTarget> targets,
                                           List<MarketingTaskSendAttempt> attempts,
                                           MarketingMessageComposer.ComposedMessage message) {
        int batchSize = outboxBatchSize(message);
        List<ProtocolMarketingMessageCommandRequest> batch = new ArrayList<>(batchSize);
        String imageBase64 = message.imageBytes() == null ? null : Base64.getEncoder().encodeToString(message.imageBytes());
        int batchCount = 0;
        int commandCount = 0;
        for (int i = 0; i < attempts.size(); i++) {
            MarketingTaskTarget target = targets.get(i);
            MarketingTaskSendAttempt attempt = attempts.get(i);
            batch.add(new ProtocolMarketingMessageCommandRequest(
                    task.getTenantId(),
                    task.getId(),
                    attempt.getId(),
                    target.getId(),
                    attempt.getRoundNo(),
                    target.getAccountId(),
                    protocolAccountId(target),
                    target.getGroupJid(),
                    message.messageType(),
                    message.text(),
                    imageBase64,
                    message.imageMimetype(),
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

    private int outboxBatchSize(MarketingMessageComposer.ComposedMessage message) {
        int configured = "IMAGE".equals(message.messageType())
                ? properties.getImageOutboxBatchSize()
                : properties.getOutboxBatchSize();
        return Math.max(1, Math.min(500, configured));
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

    private static int imageBytesLength(MarketingMessageComposer.ComposedMessage message) {
        return message.imageBytes() == null ? 0 : message.imageBytes().length;
    }

    private record EnqueueSummary(int batchSize, int batchCount, int commandCount) {
    }
}
