package com.armada.marketing.scheduler;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.service.MarketingNewGroupImmediateSendService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 扫描已到计划时间的新群第 0 轮等待记录。 */
@Component
@Profile("kafka")
public class MarketingNewGroupDelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(MarketingNewGroupDelayScheduler.class);

    private final MarketingTaskMapper taskMapper;
    private final MarketingNewGroupImmediateSendService newGroupSendService;
    private final MarketingRoundSchedulerProperties properties;

    /**
     * 创建轻量到期扫描器。
     *
     * @param taskMapper 营销任务数据访问
     * @param newGroupSendService 复用现有新群首次发送服务
     * @param properties 复用普通营销启停和扫描批量配置
     */
    public MarketingNewGroupDelayScheduler(MarketingTaskMapper taskMapper,
                                           MarketingNewGroupImmediateSendService newGroupSendService,
                                           MarketingRoundSchedulerProperties properties) {
        this.taskMapper = taskMapper;
        this.newGroupSendService = newGroupSendService;
        this.properties = properties;
    }

    /** 按计划时间扫描，并按租户、任务和动态 target 合并同账号的错峰提交批次。 */
    @Scheduled(fixedDelayString = "${armada.marketing.new-group-delay.scan-fixed-delay-ms:1000}")
    public void scanDueWaitingAttempts() {
        if (!properties.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<MarketingTaskSendAttempt> due = taskMapper.selectDueWaitingNewGroupAttempts(
                now, Math.max(1, properties.getScanLimit()));
        Map<BatchKey, List<Long>> attemptIdsByBatch = new LinkedHashMap<>();
        for (MarketingTaskSendAttempt attempt : due) {
            BatchKey key = new BatchKey(
                    attempt.getTenantId(), attempt.getMarketingTaskId(), attempt.getTargetId());
            attemptIdsByBatch.computeIfAbsent(key, ignored -> new ArrayList<>()).add(attempt.getId());
        }
        for (Map.Entry<BatchKey, List<Long>> entry : attemptIdsByBatch.entrySet()) {
            submitSafely(entry.getKey(), entry.getValue(), now);
        }
    }

    private void submitSafely(BatchKey key, List<Long> attemptIds, long submittedAt) {
        try {
            newGroupSendService.submitDueWaitingAttempts(
                    key.tenantId(), key.taskId(), attemptIds, submittedAt);
        } catch (RuntimeException ex) {
            log.warn("新群延迟发送到期提交失败 tenantId={} taskId={} targetId={} attempts={}",
                    key.tenantId(), key.taskId(), key.targetId(), attemptIds.size(), ex);
        }
    }

    private record BatchKey(Long tenantId, Long taskId, Long targetId) {
    }
}
