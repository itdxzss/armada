package com.armada.task.service.impl;

import com.armada.platform.kafka.consumer.group.ProtocolPullTaskBatchParticipantResultReportedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolPullTaskBatchParticipantResultReportedSink;
import com.armada.task.model.dto.PullTaskBatchParticipantCallback;
import com.armada.task.model.enums.PullTaskBatchParticipantProtocolOutcome;
import com.armada.task.model.enums.PullTaskParticipantExecutionState;
import com.armada.task.service.PullTaskProtocolResultCallbackService;
import org.springframework.stereotype.Component;

/** 把协议批量拉人的单成员事件转换为任务域强类型回调。 */
@Component
public class ProtocolPullTaskBatchParticipantResultAdapter
        implements ProtocolPullTaskBatchParticipantResultReportedSink {

    private final PullTaskProtocolResultCallbackService callbackService;
    private final TaskResultOwnerScopeRunner ownerScopeRunner;

    /**
     * 创建批量拉人结果适配器。
     *
     * @param callbackService 普通链接拉群协议结果状态机
     * @param ownerScopeRunner 任务 owner 数据范围执行器
     */
    public ProtocolPullTaskBatchParticipantResultAdapter(
            PullTaskProtocolResultCallbackService callbackService,
            TaskResultOwnerScopeRunner ownerScopeRunner) {
        this.callbackService = callbackService;
        this.ownerScopeRunner = ownerScopeRunner;
    }

    /** {@inheritDoc} */
    @Override
    public void handleBatchParticipantResultReported(
            ProtocolPullTaskBatchParticipantResultReportedEvent event) {
        ownerScopeRunner.runForPullTask(event.tenantId(), event.pullTaskId(),
                () -> callbackService.handlePullCallParticipant(new PullTaskBatchParticipantCallback(
                event.tenantId(), event.pullTaskId(), event.groupExecutionId(), event.pullCallId(),
                event.accountId(), event.protocolAccountId(), event.commandId(), event.attemptNo(),
                event.targetJid(), PullTaskBatchParticipantProtocolOutcome.valueOf(event.outcome()),
                PullTaskParticipantExecutionState.valueOf(event.executionState()),
                event.reasonCode(), event.reasonMessage(), event.retryable(), event.timestamp())));
    }
}
