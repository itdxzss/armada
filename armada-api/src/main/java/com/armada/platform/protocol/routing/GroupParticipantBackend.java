package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.port.GroupParticipantPort;
import java.util.List;

/** 单一协议后端的群成员变更能力。 */
public interface GroupParticipantBackend extends GroupParticipantPort {
    ProtocolBackend backend();

    GroupParticipantBatchResult updateParticipants(
            ProtocolAccountRef account,
            String groupJid,
            List<String> participants,
            GroupParticipantAction action);
}
