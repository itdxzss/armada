package com.armada.platform.protocol.routing;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolAccountIntegrity;
import com.armada.platform.protocol.port.AccountIntegrityPort;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
/** 按执行方后端分发；一期只注册 Web，不为 Android 偷用 Web 连接。 */
public final class RoutingAccountIntegrityPort implements AccountIntegrityPort {
    private final Map<ProtocolBackend, AccountIntegrityBackend> backends;
    /** 收集后端，重复注册视作装配错误。 */
    public RoutingAccountIntegrityPort(List<AccountIntegrityBackend> implementations) {
        var resolved = new EnumMap<ProtocolBackend, AccountIntegrityBackend>(ProtocolBackend.class);
        for (var implementation : implementations) {
            if (resolved.putIfAbsent(implementation.backend(), implementation) != null)
                throw new IllegalStateException("重复的档位查询后端");
        }
        backends = Map.copyOf(resolved);
    }
    @Override public boolean supports(ProtocolBackend backend) { return backends.containsKey(backend); }
    @Override public ProtocolAccountIntegrity query(ProtocolAccountRef executor, String targetPhone) {
        var backend = backends.get(executor.backend());
        if (backend == null) throw new ProtocolException(ProtocolErrorCode.UNSUPPORTED_BACKEND,
                "档位查询协议暂不支持");
        return backend.query(executor, targetPhone);
    }
}
