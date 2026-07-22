package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupCreateCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.port.GroupCreatePort;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 根据账号协议后端分发建群命令的统一端口。
 */
public final class RoutingGroupCreatePort implements GroupCreatePort {

    private static final String OPERATION = "group.create";

    private final Map<ProtocolBackend, GroupCreateBackend> backends;

    /**
     * 创建建群路由端口，并拒绝同一协议后端的重复实现。
     *
     * @param implementations 所有建群协议后端
     */
    public RoutingGroupCreatePort(List<GroupCreateBackend> implementations) {
        EnumMap<ProtocolBackend, GroupCreateBackend> resolved =
                new EnumMap<>(ProtocolBackend.class);
        if (implementations != null) {
            for (GroupCreateBackend implementation : implementations) {
                if (implementation == null || implementation.backend() == null) {
                    continue;
                }
                GroupCreateBackend previous = resolved.putIfAbsent(
                        implementation.backend(), implementation);
                if (previous != null) {
                    throw new IllegalStateException(
                            "重复的建群协议后端 backend=" + implementation.backend());
                }
            }
        }
        this.backends = Map.copyOf(resolved);
    }

    @Override
    public GroupCreateResult create(GroupCreateCommand command) {
        ProtocolBackend backend = command.account().backend();
        GroupCreateBackend implementation = backends.get(backend);
        if (implementation == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.UNSUPPORTED_BACKEND,
                    "建群协议后端未注册 backend=" + backend)
                    .withContext(backend, OPERATION, command.operationId());
        }
        return implementation.create(command);
    }
}
