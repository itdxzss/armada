package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.port.GroupApprovalPort;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 按每次实际操作账号路由，允许入群账号与原管理员使用不同协议。 */
public final class RoutingGroupApprovalPort implements GroupApprovalPort {
    private final Map<ProtocolBackend, GroupApprovalBackend> backends;

    /** 注册互不重复的协议实现。 */
    public RoutingGroupApprovalPort(List<GroupApprovalBackend> implementations) {
        var resolved = new EnumMap<ProtocolBackend, GroupApprovalBackend>(ProtocolBackend.class);
        for (var implementation : implementations) {
            if (resolved.putIfAbsent(implementation.backend(), implementation) != null) {
                throw new IllegalStateException("重复的群审批协议实现");
            }
        }
        backends = Map.copyOf(resolved);
    }
    @Override
    public String resolveGroup(ProtocolAccountRef account, String link) {
        return required(account).resolveGroup(account, link);
    }
    @Override
    public List<String> pending(ProtocolAccountRef account, String groupJid) {
        return required(account).pending(account, groupJid);
    }
    @Override
    public void approve(ProtocolAccountRef account, String groupJid, String targetJid) {
        required(account).approve(account, groupJid, targetJid);
    }
    private GroupApprovalBackend required(ProtocolAccountRef account) {
        var backend = backends.get(account.backend());
        if (backend == null) throw new ProtocolException(ProtocolErrorCode.UNSUPPORTED_BACKEND, "群审批协议未注册");
        return backend;
    }
}
