package com.armada.task.service.impl;

import com.armada.platform.kafka.consumer.group.ProtocolGroupJoinResultReportedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolGroupJoinResultReportedSink;
import com.armada.platform.kafka.consumer.group.ProtocolJoinTaskGroupJoinCorrelation;
import com.armada.platform.kafka.consumer.group.ProtocolPullTaskGroupJoinCorrelation;
import com.armada.task.model.dto.JoinTaskResultReportedEvent;
import com.armada.task.model.dto.PullTaskManagerJoinCallback;
import com.armada.task.model.enums.PullTaskManagerJoinProtocolOutcome;
import com.armada.task.service.JoinTaskResultService;
import com.armada.task.service.PullTaskManagerJoinResultService;
import org.springframework.stereotype.Component;

/** 按强类型业务关联把统一协议进群结果路由到对应任务状态机。 */
@Component
public class ProtocolGroupJoinResultRouter implements ProtocolGroupJoinResultReportedSink {

    private final JoinTaskResultService joinTaskService;
    private final PullTaskManagerJoinResultService pullTaskService;

    /**
     * 创建进群结果路由器。
     *
     * @param joinTaskService 旧进群任务结果状态机
     * @param pullTaskService 普通拉群管理员踩链接结果状态机
     */
    public ProtocolGroupJoinResultRouter(
            JoinTaskResultService joinTaskService,
            PullTaskManagerJoinResultService pullTaskService) {
        this.joinTaskService = joinTaskService;
        this.pullTaskService = pullTaskService;
    }

    /** {@inheritDoc} */
    @Override
    public void handleJoinResultReported(ProtocolGroupJoinResultReportedEvent event) {
        if (event.correlation() instanceof ProtocolJoinTaskGroupJoinCorrelation correlation) {
            joinTaskService.apply(toJoinTaskEvent(event, correlation));
            return;
        }
        if (event.correlation() instanceof ProtocolPullTaskGroupJoinCorrelation correlation) {
            pullTaskService.apply(toPullTaskCallback(event, correlation));
            return;
        }
        throw new IllegalArgumentException("不支持的进群结果关联类型");
    }

    private static JoinTaskResultReportedEvent toJoinTaskEvent(
            ProtocolGroupJoinResultReportedEvent event,
            ProtocolJoinTaskGroupJoinCorrelation correlation) {
        return new JoinTaskResultReportedEvent(
                event.eventId(), event.tenantId(), correlation.joinTaskId(),
                correlation.joinTaskResultId(), event.accountId(), event.protocolAccountId(),
                event.commandId(), event.attemptNo(), event.outcome(), event.groupJid(),
                event.reasonCode(), event.reasonMessage(), event.retryable(),
                event.timestamp(), event.workerId());
    }

    private static PullTaskManagerJoinCallback toPullTaskCallback(
            ProtocolGroupJoinResultReportedEvent event,
            ProtocolPullTaskGroupJoinCorrelation correlation) {
        return new PullTaskManagerJoinCallback(
                event.tenantId(), correlation.pullTaskId(), correlation.groupExecutionId(),
                correlation.actionId(), event.commandId(),
                PullTaskManagerJoinProtocolOutcome.valueOf(event.outcome()), event.groupJid(),
                event.reasonCode(), event.reasonMessage(), event.retryable(), event.timestamp());
    }
}
