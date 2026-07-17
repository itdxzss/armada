package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolAccountRuntimeStatus;
import com.armada.platform.protocol.port.AccountRuntimeStatusPort;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 根据账号协议后端分发运行态查询的统一端口。
 */
public final class RoutingAccountRuntimeStatusPort implements AccountRuntimeStatusPort {

    private static final String STATUS_OPERATION = "account.status";
    private static final String ACCOUNT_OPERATION_PREFIX = "account:";

    private final Map<ProtocolBackend, AccountRuntimeStatusBackend> backends;

    /**
     * 创建账号运行态路由并校验每个协议后端只有一个实现。
     *
     * @param implementations Spring 收集的账号运行态 backend 实现
     * @throws IllegalStateException 同一协议后端被重复注册时抛出
     */
    public RoutingAccountRuntimeStatusPort(List<AccountRuntimeStatusBackend> implementations) {
        EnumMap<ProtocolBackend, AccountRuntimeStatusBackend> resolved =
                new EnumMap<>(ProtocolBackend.class);
        if (implementations != null) {
            for (AccountRuntimeStatusBackend implementation : implementations) {
                if (implementation == null || implementation.backend() == null) {
                    continue;
                }
                AccountRuntimeStatusBackend previous =
                        resolved.putIfAbsent(implementation.backend(), implementation);
                if (previous != null) {
                    throw new IllegalStateException(
                            "重复的账号运行态协议后端 backend=" + implementation.backend());
                }
            }
        }
        this.backends = Map.copyOf(resolved);
    }

    /**
     * 把状态查询路由到账号引用指定的协议后端。
     *
     * @param account 统一协议账号引用
     * @return 归一化后的账号运行态
     * @throws ProtocolException 对应协议后端未注册时抛出 UNSUPPORTED_BACKEND
     */
    @Override
    public ProtocolAccountRuntimeStatus status(ProtocolAccountRef account) {
        ProtocolBackend backend = account.backend();
        AccountRuntimeStatusBackend implementation = backends.get(backend);
        if (implementation == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.UNSUPPORTED_BACKEND,
                    "账号运行态协议后端未注册 backend=" + backend)
                    .withContext(
                            backend,
                            STATUS_OPERATION,
                            ACCOUNT_OPERATION_PREFIX + account.armadaAccountId());
        }
        return implementation.status(account);
    }
}
