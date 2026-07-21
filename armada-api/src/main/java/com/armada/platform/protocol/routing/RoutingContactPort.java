package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ContactSaveCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.port.ContactPort;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 根据账号协议后端分发联系人保存命令的统一端口。
 */
public final class RoutingContactPort implements ContactPort {

    private static final String OPERATION = "contact.save";

    private final Map<ProtocolBackend, ContactBackend> backends;

    /**
     * 创建联系人路由端口，并拒绝同一协议后端的重复实现。
     *
     * @param implementations 所有联系人保存协议后端
     */
    public RoutingContactPort(List<ContactBackend> implementations) {
        EnumMap<ProtocolBackend, ContactBackend> resolved =
                new EnumMap<>(ProtocolBackend.class);
        if (implementations != null) {
            for (ContactBackend implementation : implementations) {
                if (implementation == null || implementation.backend() == null) {
                    continue;
                }
                ContactBackend previous = resolved.putIfAbsent(
                        implementation.backend(), implementation);
                if (previous != null) {
                    throw new IllegalStateException(
                            "重复的联系人协议后端 backend=" + implementation.backend());
                }
            }
        }
        this.backends = Map.copyOf(resolved);
    }

    @Override
    public void save(ContactSaveCommand command) {
        ProtocolBackend backend = command.account().backend();
        ContactBackend implementation = backends.get(backend);
        if (implementation == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.UNSUPPORTED_BACKEND,
                    "联系人协议后端未注册 backend=" + backend)
                    .withContext(backend, OPERATION, command.operationId());
        }
        implementation.save(command);
    }
}
