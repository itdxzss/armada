package com.armada.platform.protocol.model.command;

import com.armada.platform.protocol.model.enums.ProtocolBackend;
import java.util.List;

/** 按需读取单群资料与邀请码的协议命令。 */
public record ProtocolGroupSnapshotCommandRequest(
        Long tenantId,
        Long accountId,
        Long groupLinkId,
        String groupJid,
        List<String> scopes,
        String source,
        String taskType,
        Long taskId,
        int attemptNo,
        String protocolAccountId,
        String wsPhone,
        ProtocolBackend protocolBackend) {
}
