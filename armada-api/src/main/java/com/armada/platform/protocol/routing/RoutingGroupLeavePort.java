package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.port.GroupLeavePort;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 按操作账号协议类型路由退群。 */
public final class RoutingGroupLeavePort implements GroupLeavePort {

    private final Map<ProtocolBackend, GroupLeaveBackend> backends;

    public RoutingGroupLeavePort(List<GroupLeaveBackend> implementations) {
        EnumMap<ProtocolBackend, GroupLeaveBackend> resolved = new EnumMap<>(ProtocolBackend.class);
        if (implementations != null) {
            for (GroupLeaveBackend implementation : implementations) {
                if (implementation == null || implementation.backend() == null) {
                    continue;
                }
                if (resolved.putIfAbsent(implementation.backend(), implementation) != null) {
                    throw new IllegalStateException("重复的退群协议后端 backend=" + implementation.backend());
                }
            }
        }
        this.backends = Map.copyOf(resolved);
    }

    @Override
    public void leave(ProtocolAccountRef account, String groupJid) {
        ProtocolBackend backend = account == null ? null : account.backend();
        GroupLeaveBackend implementation = backends.get(backend);
        if (implementation == null) {
            throw new ProtocolException(ProtocolErrorCode.UNSUPPORTED_BACKEND,
                    "退群协议后端未注册 backend=" + backend)
                    .withContext(backend, "group.leave", null);
        }
        implementation.leave(account, groupJid);
    }
}
