package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskMemberQueryOutcome;
import com.armada.task.model.enums.PullTaskMemberQueryPurpose;
import java.util.List;

/** 普通拉群异步成员查询的强关联协议回调。 */
public record PullTaskMemberQueryCallback(
        String eventId,
        long tenantId,
        long pullTaskId,
        long groupExecutionId,
        long queryId,
        PullTaskMemberQueryPurpose purpose,
        long accountId,
        String protocolAccountId,
        String protocolBackend,
        String commandId,
        int attemptNo,
        PullTaskMemberQueryOutcome outcome,
        String groupJid,
        List<PullTaskMemberFact> members,
        String reasonCode,
        String reasonMessage,
        boolean retryable,
        long occurredAt
) {
}
