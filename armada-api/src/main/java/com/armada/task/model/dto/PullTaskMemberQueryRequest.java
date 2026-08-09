package com.armada.task.model.dto;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.task.model.enums.PullTaskMemberQueryPurpose;
import java.util.List;

/** 普通拉群阶段或收敛逻辑请求读取成员事实。 */
public record PullTaskMemberQueryRequest(
        Long taskId,
        Long groupExecutionId,
        String businessKey,
        PullTaskMemberQueryPurpose purpose,
        ProtocolAccountRef actor,
        String groupJid,
        List<String> targetJids
) {
}
