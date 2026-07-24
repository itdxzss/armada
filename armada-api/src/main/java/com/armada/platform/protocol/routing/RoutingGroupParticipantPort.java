package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.port.GroupParticipantPort;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 按操作账号协议类型路由群成员动作。 */
public final class RoutingGroupParticipantPort implements GroupParticipantPort {

    private final Map<ProtocolBackend, GroupParticipantBackend> backends;

    public RoutingGroupParticipantPort(List<GroupParticipantBackend> implementations) {
        EnumMap<ProtocolBackend, GroupParticipantBackend> resolved = new EnumMap<>(ProtocolBackend.class);
        if (implementations != null) {
            for (GroupParticipantBackend implementation : implementations) {
                if (implementation == null || implementation.backend() == null) {
                    continue;
                }
                if (resolved.putIfAbsent(implementation.backend(), implementation) != null) {
                    throw new IllegalStateException("重复的群成员协议后端 backend=" + implementation.backend());
                }
            }
        }
        this.backends = Map.copyOf(resolved);
    }

    @Override
    public GroupParticipantBatchResult updateParticipants(
            ProtocolAccountRef account,
            String groupJid,
            List<String> participants,
            GroupParticipantAction action) {
        ProtocolBackend backend = account == null ? null : account.backend();
        GroupParticipantBackend implementation = backends.get(backend);
        if (implementation == null) {
            throw unsupported(backend, action);
        }
        return implementation.updateParticipants(account, groupJid, participants, action);
    }

    private static ProtocolException unsupported(ProtocolBackend backend, GroupParticipantAction action) {
        return new ProtocolException(
                ProtocolErrorCode.UNSUPPORTED_BACKEND,
                "群成员协议后端未注册 backend=" + backend)
                .withContext(backend, "group.participants." + (action == null ? "unknown" : action.wireValue()), null);
    }
}
