package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.platform.protocol.port.GroupInvitePort;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 按操作账号协议类型路由邀请链接查询。 */
public final class RoutingGroupInvitePort implements GroupInvitePort {

    private final Map<ProtocolBackend, GroupInviteBackend> backends;

    public RoutingGroupInvitePort(List<GroupInviteBackend> implementations) {
        EnumMap<ProtocolBackend, GroupInviteBackend> resolved = new EnumMap<>(ProtocolBackend.class);
        if (implementations != null) {
            for (GroupInviteBackend implementation : implementations) {
                if (implementation == null || implementation.backend() == null) {
                    continue;
                }
                if (resolved.putIfAbsent(implementation.backend(), implementation) != null) {
                    throw new IllegalStateException("重复的群邀请协议后端 backend=" + implementation.backend());
                }
            }
        }
        this.backends = Map.copyOf(resolved);
    }

    @Override
    public GroupInviteResult getInvite(ProtocolAccountRef account, String groupJid) {
        ProtocolBackend backend = account == null ? null : account.backend();
        GroupInviteBackend implementation = backends.get(backend);
        if (implementation == null) {
            throw new ProtocolException(ProtocolErrorCode.UNSUPPORTED_BACKEND,
                    "群邀请协议后端未注册 backend=" + backend)
                    .withContext(backend, "group.invite", null);
        }
        return implementation.getInvite(account, groupJid);
    }
}
