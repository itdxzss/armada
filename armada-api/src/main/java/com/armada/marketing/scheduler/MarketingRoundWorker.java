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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
@Profile("kafka")
public class MarketingRoundWorker {
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
        if (task == null || !Integer.valueOf(MarketingTaskStatus.SENDING.code()).equals(task.getStatus())) {
            return;
        }
        List<MarketingTaskTarget> targets = taskMapper.selectTargetsByTaskId(taskId);
        if (targets.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long nextRoundAt = now + sendIntervalSeconds(task) * 1000L;
        long unfinished = taskMapper.countUnfinishedAttempts(taskId);
        if (unfinished >= (long) Math.max(1, properties.getBacklogMultiplier()) * targets.size()) {
            taskMapper.postponeDueRound(taskId, now, nextRoundAt);
            return;
        }
        int claimed = taskMapper.claimDueRound(taskId, now, nextRoundAt);
        if (claimed == 0) {
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
        enqueueCommands(task, targets, attempts, message);
    }

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

    private void enqueueCommands(MarketingTask task,
                                 List<MarketingTaskTarget> targets,
                                 List<MarketingTaskSendAttempt> attempts,
                                 MarketingMessageComposer.ComposedMessage message) {
        int batchSize = Math.max(1, Math.min(500, properties.getOutboxBatchSize()));
        List<ProtocolMarketingMessageCommandRequest> batch = new ArrayList<>(batchSize);
        String imageBase64 = message.imageBytes() == null ? null : Base64.getEncoder().encodeToString(message.imageBytes());
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
                batch = new ArrayList<>(batchSize);
            }
        }
        if (!batch.isEmpty()) {
            outboxService.enqueueMarketingMessageCommands(batch);
        }
    }

    private static String protocolAccountId(MarketingTaskTarget target) {
        if (StringUtils.hasText(target.getProtocolAccountId())) {
            return target.getProtocolAccountId();
        }
        if (StringUtils.hasText(target.getAccountPhone())) {
            return "acc_" + target.getAccountPhone();
        }
        throw new BusinessException(ErrorCode.VALIDATION, "营销目标缺少协议账号ID: targetId=" + target.getId());
    }

    private static String newCommandId() {
        return "cmd_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static long sendIntervalSeconds(MarketingTask task) {
        Integer configured = task.getSendIntervalSeconds();
        return configured == null || configured < 1 ? 30L : configured.longValue();
    }
}
