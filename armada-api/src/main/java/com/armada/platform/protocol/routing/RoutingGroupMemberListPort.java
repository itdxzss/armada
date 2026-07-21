package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupMemberListQuery;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.GroupMemberListPort;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 根据账号协议后端分发群成员列表查询的统一端口。
 */
public final class RoutingGroupMemberListPort implements GroupMemberListPort {

    private static final String OPERATION = "group.members.list";

    private final Map<ProtocolBackend, GroupMemberListBackend> backends;

    /**
     * 创建群成员查询路由端口，并拒绝同一协议后端的重复实现。
     *
     * @param implementations 所有群成员查询协议后端
     */
    public RoutingGroupMemberListPort(List<GroupMemberListBackend> implementations) {
        EnumMap<ProtocolBackend, GroupMemberListBackend> resolved =
                new EnumMap<>(ProtocolBackend.class);
        if (implementations != null) {
            for (GroupMemberListBackend implementation : implementations) {
                if (implementation == null || implementation.backend() == null) {
                    continue;
                }
                GroupMemberListBackend previous = resolved.putIfAbsent(
                        implementation.backend(), implementation);
                if (previous != null) {
                    throw new IllegalStateException(
                            "重复的群成员查询协议后端 backend=" + implementation.backend());
                }
            }
        }
        this.backends = Map.copyOf(resolved);
    }

    @Override
    public List<GroupParticipantResult> list(GroupMemberListQuery query) {
        ProtocolBackend backend = query.account().backend();
        GroupMemberListBackend implementation = backends.get(backend);
        if (implementation == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.UNSUPPORTED_BACKEND,
                    "群成员查询协议后端未注册 backend=" + backend)
                    .withContext(backend, OPERATION, query.operationId());
        }
        return implementation.list(query);
    }
}
