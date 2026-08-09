package com.armada.task.model.dto;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.task.model.enums.PullTaskMemberQueryPurpose;
import java.util.List;

/** 创建一次普通拉群异步成员查询所需的冻结事实。 */
public record PullTaskMemberQueryCreateRequest(
        Long taskId,
        Long groupExecutionId,
        String businessKey,
        PullTaskMemberQueryPurpose purpose,
        ProtocolAccountRef actor,
        String groupJid,
        List<String> targetJids,
        long requestedAt,
        long deadlineAt
) {
}
