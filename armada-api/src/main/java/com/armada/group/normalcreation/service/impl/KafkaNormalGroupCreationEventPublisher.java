package com.armada.group.normalcreation.service.impl;

import com.armada.group.normalcreation.mapper.NormalGroupCreationMapper;
import com.armada.group.normalcreation.model.dto.NormalGroupCreationCommand;
import com.armada.group.normalcreation.service.NormalGroupCreationEventPublisher;
import com.armada.platform.dispatch.mapper.NormalGroupCreationDispatchMapper;
import com.armada.platform.dispatch.model.NormalGroupCreationDispatchCandidate;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Kafka 三阶段消息发布器，并提供低频、索引命中的漏发补偿。 */
@Component
public class KafkaNormalGroupCreationEventPublisher implements NormalGroupCreationEventPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(KafkaNormalGroupCreationEventPublisher.class);
    private static final int SCHEMA_VERSION = 1;
    private static final int COMPENSATION_BATCH_SIZE = 100;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final NormalGroupCreationMapper mapper;
    private final NormalGroupCreationDispatchMapper dispatchMapper;
    private final String prepareTopic;
    private final String createTopic;
    private final String postProcessTopic;
    private final long processingTimeoutMs;
    private final int maxStageAttempts;

    public KafkaNormalGroupCreationEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            NormalGroupCreationMapper mapper,
            NormalGroupCreationDispatchMapper dispatchMapper,
            @Value("${armada.normal-group-creation.kafka.prepare-topic:group.normal-creation.contact-prepare.v1}")
            String prepareTopic,
            @Value("${armada.normal-group-creation.kafka.create-topic:group.normal-creation.create.v1}")
            String createTopic,
            @Value("${armada.normal-group-creation.kafka.post-process-topic:group.normal-creation.post-process.v1}")
            String postProcessTopic,
            @Value("${armada.normal-group-creation.processing-timeout-ms:300000}")
            long processingTimeoutMs,
            @Value("${armada.normal-group-creation.max-stage-attempts:3}")
            int maxStageAttempts) {
        this.kafkaTemplate = kafkaTemplate;
        this.mapper = mapper;
        this.dispatchMapper = dispatchMapper;
        this.prepareTopic = prepareTopic;
        this.createTopic = createTopic;
        this.postProcessTopic = postProcessTopic;
        this.processingTimeoutMs = Math.max(processingTimeoutMs, 60_000L);
        this.maxStageAttempts = Math.max(maxStageAttempts, 1);
    }

    @Override
    public void publish(
            String action, Long tenantId, Long taskId, Long itemId, Long creatorAccountId) {
        long now = System.currentTimeMillis();
        NormalGroupCreationCommand command = new NormalGroupCreationCommand(
                SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                tenantId,
                taskId,
                itemId,
                action,
                now);
        try {
            kafkaTemplate.send(topic(action), tenantId + ":" + creatorAccountId, command)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.warn("新建普群阶段消息异步发布失败 tenantId={} taskId={} itemId={} action={}",
                                    tenantId, taskId, itemId, action, error);
                            return;
                        }
                        markDispatchedInTenant(tenantId, itemId, action);
                    });
        } catch (RuntimeException ex) {
            throw new IllegalStateException("新建普群阶段消息发布失败", ex);
        }
    }

    /** 每分钟只查询最多一百条明确待发布记录，不扫描运行中业务明细。 */
    @Scheduled(fixedDelayString = "${armada.normal-group-creation.dispatch-recovery-delay-ms:60000}")
    public void recoverPendingDispatches() {
        long now = System.currentTimeMillis();
        recoverExpiredProcessing(now);
        List<NormalGroupCreationDispatchCandidate> rows =
                dispatchMapper.selectPendingDispatches(now, COMPENSATION_BATCH_SIZE);
        for (NormalGroupCreationDispatchCandidate row : rows) {
            Long previousTenant = TenantContext.get();
            try {
                TenantContext.set(row.tenantId());
                publish(row.dispatchStage(), row.tenantId(), row.taskId(),
                        row.itemId(), row.creatorAccountId());
            } catch (RuntimeException ex) {
                log.warn("新建普群阶段消息补偿失败 tenantId={} taskId={} itemId={} stage={}",
                        row.tenantId(), row.taskId(), row.itemId(), row.dispatchStage(), ex);
            } finally {
                if (previousTenant == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.set(previousTenant);
                }
            }
        }
    }

    private void recoverExpiredProcessing(long now) {
        long processingBefore = now - processingTimeoutMs;
        List<NormalGroupCreationDispatchCandidate> rows =
                dispatchMapper.selectExpiredProcessing(
                        processingBefore, COMPENSATION_BATCH_SIZE);
        for (NormalGroupCreationDispatchCandidate row : rows) {
            Long previousTenant = TenantContext.get();
            try {
                TenantContext.set(row.tenantId());
                int updated = mapper.recoverExpiredProcessing(
                        row.itemId(), row.currentStep(), processingBefore, maxStageAttempts, now);
                if (updated == 1) {
                    mapper.refreshTaskSummary(row.taskId(), now);
                    log.warn("新建普群执行租约过期已按阶段安全收敛 tenantId={} taskId={} "
                                    + "itemId={} currentStep={}",
                            row.tenantId(), row.taskId(), row.itemId(), row.currentStep());
                }
            } catch (RuntimeException ex) {
                log.warn("新建普群执行租约恢复失败 tenantId={} taskId={} itemId={} step={}",
                        row.tenantId(), row.taskId(), row.itemId(), row.currentStep(), ex);
            } finally {
                restoreTenant(previousTenant);
            }
        }
    }

    private void markDispatchedInTenant(Long tenantId, Long itemId, String stage) {
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            mapper.markDispatched(itemId, stage, System.currentTimeMillis());
        } catch (RuntimeException ex) {
            log.warn("新建普群消息已发出但派发状态回写失败 tenantId={} itemId={} stage={}",
                    tenantId, itemId, stage, ex);
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }

    private String topic(String action) {
        return switch (action) {
            case "PREPARE" -> prepareTopic;
            case "CREATE" -> createTopic;
            case "POST_PROCESS" -> postProcessTopic;
            default -> throw new IllegalArgumentException("未知新建普群阶段: " + action);
        };
    }
}
