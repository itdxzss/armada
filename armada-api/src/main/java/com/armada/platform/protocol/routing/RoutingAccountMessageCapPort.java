package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolMessageCap;
import com.armada.platform.protocol.port.AccountMessageCapPort;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 根据账号协议后端分发新聊天额度查询的统一端口。
 */
public final class RoutingAccountMessageCapPort implements AccountMessageCapPort {

    private static final String MESSAGE_CAP_OPERATION = "account.messageCap";
    private static final String ACCOUNT_OPERATION_PREFIX = "account:";

    private final Map<ProtocolBackend, AccountMessageCapBackend> backends;

    /**
     * 创建额度查询路由并校验每个协议后端只有一个实现。
     *
     * @param implementations Spring 收集的额度查询 backend 实现
     * @throws IllegalStateException 同一协议后端被重复注册时抛出
     */
    public RoutingAccountMessageCapPort(List<AccountMessageCapBackend> implementations) {
        EnumMap<ProtocolBackend, AccountMessageCapBackend> resolved =
                new EnumMap<>(ProtocolBackend.class);
        if (implementations != null) {
            for (AccountMessageCapBackend implementation : implementations) {
                if (implementation == null || implementation.backend() == null) {
                    continue;
                }
                if (resolved.putIfAbsent(implementation.backend(), implementation) != null) {
                    throw new IllegalStateException(
                            "重复的账号额度查询协议后端 backend=" + implementation.backend());
                }
            }
        }
        this.backends = Map.copyOf(resolved);
    }

    /**
     * 把额度查询路由到账号引用指定的协议后端。
     *
     * @param account 统一协议账号引用
     * @param debug 是否附带原始响应
     * @return 归一化后的额度结果
     * @throws ProtocolException 对应协议后端未注册时抛出 UNSUPPORTED_BACKEND
     */
    @Override
    public ProtocolMessageCap query(ProtocolAccountRef account, boolean debug) {
        AccountMessageCapBackend implementation = backends.get(account.backend());
        if (implementation == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.UNSUPPORTED_BACKEND,
                    "账号额度查询协议后端未注册 backend=" + account.backend())
                    .withContext(
                            account.backend(),
                            MESSAGE_CAP_OPERATION,
                            ACCOUNT_OPERATION_PREFIX + account.armadaAccountId());
        }
        return implementation.query(account, debug);
    }
}
