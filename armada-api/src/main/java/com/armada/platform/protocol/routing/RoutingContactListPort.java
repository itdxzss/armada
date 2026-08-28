package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.AccountContactSnapshot;
import com.armada.platform.protocol.port.ContactListPort;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 根据账号协议后端分发通讯录读取的统一端口。
 */
public final class RoutingContactListPort implements ContactListPort {

    private static final String OPERATION = "contact.list";

    private final Map<ProtocolBackend, ContactListBackend> backends;

    /**
     * 创建通讯录路由端口，并拒绝同一协议后端的重复实现。
     *
     * @param implementations 所有通讯录读取协议后端
     */
    public RoutingContactListPort(List<ContactListBackend> implementations) {
        EnumMap<ProtocolBackend, ContactListBackend> resolved =
                new EnumMap<>(ProtocolBackend.class);
        if (implementations != null) {
            for (ContactListBackend implementation : implementations) {
                if (implementation == null || implementation.backend() == null) {
                    continue;
                }
                ContactListBackend previous = resolved.putIfAbsent(
                        implementation.backend(), implementation);
                if (previous != null) {
                    throw new IllegalStateException(
                            "重复的通讯录协议后端 backend=" + implementation.backend());
                }
            }
        }
        this.backends = Map.copyOf(resolved);
    }

    @Override
    public AccountContactSnapshot list(ProtocolAccountRef account) {
        ProtocolBackend backend = account.backend();
        ContactListBackend implementation = backends.get(backend);
        if (implementation == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.UNSUPPORTED_BACKEND,
                    "通讯录协议后端未注册 backend=" + backend)
                    .withContext(backend, OPERATION, "account:" + account.armadaAccountId());
        }
        return implementation.list(account);
    }
}
