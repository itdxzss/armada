package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.port.GroupJoinPort;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 根据账号协议后端分发进群命令的统一端口。
 */
public final class RoutingGroupJoinPort implements GroupJoinPort {

    private final Map<ProtocolBackend, GroupJoinBackend> backends;

    public RoutingGroupJoinPort(List<GroupJoinBackend> implementations) {
        EnumMap<ProtocolBackend, GroupJoinBackend> resolved = new EnumMap<>(ProtocolBackend.class);
        if (implementations != null) {
            for (GroupJoinBackend implementation : implementations) {
                // Spring 收集到空实现或实现未声明后端时忽略，避免无意义注册。
                if (implementation == null || implementation.backend() == null) {
                    continue;
                }
                // 同一后端只能存在一个实现，否则实际调用目标不确定，启动时立即失败。
                GroupJoinBackend previous = resolved.putIfAbsent(implementation.backend(), implementation);
                if (previous != null) {
                    throw new IllegalStateException("重复的进群协议后端 backend=" + implementation.backend());
                }
            }
        }
        this.backends = Map.copyOf(resolved);
    }

    @Override
    public GroupJoinResult join(GroupJoinCommand command) {
        // 路由依据只来自命令中的账号引用，避免调用方另外传 backend 导致两处信息不一致。
        ProtocolBackend backend = command.account().backend();
        GroupJoinBackend implementation = backends.get(backend);
        if (implementation == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.UNSUPPORTED_BACKEND,
                    "进群协议后端未注册 backend=" + backend)
                    .withContext(backend, "group.join", command.operationId());
        }
        return implementation.join(command);
    }
}
