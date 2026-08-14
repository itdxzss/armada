package com.armada.task.service.impl;

import com.armada.platform.kafka.consumer.group.ProtocolGroupMembersResultReportedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolGroupMembersResultReportedSink;
import com.armada.task.model.dto.PullTaskMemberFact;
import com.armada.task.model.dto.PullTaskMemberQueryCallback;
import com.armada.task.model.enums.PullTaskMemberQueryOutcome;
import com.armada.task.model.enums.PullTaskMemberQueryPurpose;
import com.armada.task.service.PullTaskMemberQueryResultService;
import org.springframework.stereotype.Component;

/** 把协议群成员事件转换成拉群任务域的强类型回调。 */
@Component
public class ProtocolGroupMembersResultAdapter implements ProtocolGroupMembersResultReportedSink {

    private final PullTaskMemberQueryResultService resultService;

    public ProtocolGroupMembersResultAdapter(PullTaskMemberQueryResultService resultService) {
        this.resultService = resultService;
    }

    @Override
    public void handleMembersResultReported(ProtocolGroupMembersResultReportedEvent event) {
        resultService.apply(new PullTaskMemberQueryCallback(
                event.eventId(), event.tenantId(), event.pullTaskId(),
                event.groupExecutionId(), event.queryId(),
                PullTaskMemberQueryPurpose.valueOf(event.purpose()), event.accountId(),
                event.protocolAccountId(), event.protocolBackend(), event.commandId(),
                event.attemptNo(), PullTaskMemberQueryOutcome.valueOf(event.outcome()),
                event.groupJid(), event.members().stream().map(fact -> new PullTaskMemberFact(
                fact.targetJid(), fact.participantJid(), fact.phoneNumber(),
                fact.inGroup(), fact.admin())).toList(),
                event.reasonCode(), event.reasonMessage(), event.retryable(), event.timestamp()));
    }
}
