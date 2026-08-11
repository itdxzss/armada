package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupPictureResult;
import com.armada.platform.protocol.port.GroupProfilePort;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 按操作账号协议类型路由群资料操作。 */
public final class RoutingGroupProfilePort implements GroupProfilePort {

    private final Map<ProtocolBackend, GroupProfileBackend> backends;

    public RoutingGroupProfilePort(List<GroupProfileBackend> implementations) {
        EnumMap<ProtocolBackend, GroupProfileBackend> resolved = new EnumMap<>(ProtocolBackend.class);
        if (implementations != null) {
            for (GroupProfileBackend implementation : implementations) {
                if (implementation == null || implementation.backend() == null) {
                    continue;
                }
                if (resolved.putIfAbsent(implementation.backend(), implementation) != null) {
                    throw new IllegalStateException("重复的群资料协议后端 backend=" + implementation.backend());
                }
            }
        }
        this.backends = Map.copyOf(resolved);
    }

    @Override
    public void updateSubject(ProtocolAccountRef account, String groupJid, String subject) {
        backend(account).updateSubject(account, groupJid, subject);
    }

    @Override
    public void updateDescription(ProtocolAccountRef account, String groupJid, String description) {
        backend(account).updateDescription(account, groupJid, description);
    }

    @Override
    public void updateAnnouncementText(ProtocolAccountRef account, String groupJid, String text) {
        backend(account).updateAnnouncementText(account, groupJid, text);
    }

    @Override
    public GroupPictureResult updatePicture(
            ProtocolAccountRef account, String groupJid, String url, String base64) {
        return backend(account).updatePicture(account, groupJid, url, base64);
    }

    @Override
    public String getPictureUrl(ProtocolAccountRef account, String groupJid) {
        return backend(account).getPictureUrl(account, groupJid);
    }

    private GroupProfileBackend backend(ProtocolAccountRef account) {
        ProtocolBackend backend = account == null ? null : account.backend();
        GroupProfileBackend implementation = backends.get(backend);
        if (implementation == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.UNSUPPORTED_BACKEND,
                    "群资料协议后端未注册 backend=" + backend)
                    .withContext(backend, "group.profile", null);
        }
        return implementation;
    }
}
