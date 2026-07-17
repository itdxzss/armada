package com.armada.task.service.impl;

import com.armada.platform.kafka.consumer.group.ProtocolGroupJoinResultReportedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolGroupJoinResultReportedSink;
import com.armada.task.model.dto.JoinTaskResultReportedEvent;
import com.armada.task.service.JoinTaskResultService;
import org.springframework.stereotype.Component;

/**
 * 把平台 Kafka 消费层事件转换为任务域事件。
 *
 * <p>适配器只隔离包依赖和模型所有权，不做第二套状态判断；所有字段原样传递给统一结果状态机。</p>
 */
@Component
public class JoinTaskResultReportedSinkAdapter implements ProtocolGroupJoinResultReportedSink {

    /** 统一进群结果状态机。 */
    private final JoinTaskResultService resultService;

    /**
     * 创建进群结果消费适配器。
     *
     * @param resultService 任务域统一结果状态机
     */
    public JoinTaskResultReportedSinkAdapter(JoinTaskResultService resultService) {
        this.resultService = resultService;
    }

    /**
     * {@inheritDoc}
     *
     * <p>这里只转换边界模型，幂等锁定和重试决策由 {@link JoinTaskResultService} 完成。</p>
     */
    @Override
    public void handleJoinResultReported(ProtocolGroupJoinResultReportedEvent event) {
        resultService.apply(new JoinTaskResultReportedEvent(
                event.eventId(),
                event.tenantId(),
                event.joinTaskId(),
                event.joinTaskResultId(),
                event.accountId(),
                event.protocolAccountId(),
                event.commandId(),
                event.attemptNo(),
                event.outcome(),
                event.groupJid(),
                event.reasonCode(),
                event.reasonMessage(),
                event.retryable(),
                event.timestamp(),
                event.workerId()));
    }
}
