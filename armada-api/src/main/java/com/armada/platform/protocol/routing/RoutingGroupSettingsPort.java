package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.port.GroupSettingsPort;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 按操作账号协议类型路由群设置。 */
public final class RoutingGroupSettingsPort implements GroupSettingsPort {

    private final Map<ProtocolBackend, GroupSettingsBackend> backends;

    public RoutingGroupSettingsPort(List<GroupSettingsBackend> implementations) {
        EnumMap<ProtocolBackend, GroupSettingsBackend> resolved = new EnumMap<>(ProtocolBackend.class);
        if (implementations != null) {
            for (GroupSettingsBackend implementation : implementations) {
                if (implementation == null || implementation.backend() == null) {
                    continue;
                }
                if (resolved.putIfAbsent(implementation.backend(), implementation) != null) {
                    throw new IllegalStateException("重复的群设置协议后端 backend=" + implementation.backend());
                }
            }
        }
        this.backends = Map.copyOf(resolved);
    }

    @Override
    public void setEphemeralDuration(ProtocolAccountRef account, String groupJid, int durationSeconds) {
        backend(account).setEphemeralDuration(account, groupJid, durationSeconds);
    }

    @Override
    public void setEditGroupSettingsAllowed(ProtocolAccountRef account, String groupJid, boolean enabled) {
        backend(account).setEditGroupSettingsAllowed(account, groupJid, enabled);
    }

    @Override
    public void setSendMessagesAllowed(ProtocolAccountRef account, String groupJid, boolean enabled) {
        backend(account).setSendMessagesAllowed(account, groupJid, enabled);
    }

    @Override
    public void setAddMembersAllowed(ProtocolAccountRef account, String groupJid, boolean enabled) {
        backend(account).setAddMembersAllowed(account, groupJid, enabled);
    }

    @Override
    public void setInviteViaLinkAllowed(ProtocolAccountRef account, String groupJid, boolean enabled) {
        backend(account).setInviteViaLinkAllowed(account, groupJid, enabled);
    }

    @Override
    public void setJoinApprovalEnabled(ProtocolAccountRef account, String groupJid, boolean enabled) {
        backend(account).setJoinApprovalEnabled(account, groupJid, enabled);
    }

    private GroupSettingsBackend backend(ProtocolAccountRef account) {
        ProtocolBackend backend = account == null ? null : account.backend();
        GroupSettingsBackend implementation = backends.get(backend);
        if (implementation == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.UNSUPPORTED_BACKEND,
                    "群设置协议后端未注册 backend=" + backend)
                    .withContext(backend, "group.settings", null);
        }
        return implementation;
    }
}
