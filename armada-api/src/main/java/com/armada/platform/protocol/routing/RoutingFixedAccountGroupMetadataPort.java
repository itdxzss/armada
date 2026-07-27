package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 根据固定账号协议后端分发只读群 metadata 的统一端口。
 */
public final class RoutingFixedAccountGroupMetadataPort
        implements FixedAccountGroupMetadataPort {

    private static final String OPERATION = "group.metadata.get";

    private final Map<ProtocolBackend, FixedAccountGroupMetadataBackend> backends;

    /**
     * 创建只读群 metadata 路由端口，并拒绝同一协议后端的重复实现。
     *
     * @param candidates 所有固定账号只读群 metadata 后端
     */
    public RoutingFixedAccountGroupMetadataPort(
            List<FixedAccountGroupMetadataBackend> candidates) {
        EnumMap<ProtocolBackend, FixedAccountGroupMetadataBackend> mapped =
                new EnumMap<>(ProtocolBackend.class);
        if (candidates != null) {
            for (FixedAccountGroupMetadataBackend candidate : candidates) {
                if (candidate == null || candidate.backend() == null) {
                    continue;
                }
                FixedAccountGroupMetadataBackend previous = mapped.putIfAbsent(
                        candidate.backend(), candidate);
                if (previous != null) {
                    throw new IllegalStateException(
                            "固定账号群 metadata backend 重复注册: " + candidate.backend());
                }
            }
        }
        this.backends = Map.copyOf(mapped);
    }

    @Override
    public GroupMetadataResult getMetadata(
            ProtocolAccountRef account,
            String groupJid) {
        if (account == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.BAD_REQUEST,
                    "固定操作账号不能为空");
        }
        ProtocolBackend backend = account.backend();
        FixedAccountGroupMetadataBackend selected = backends.get(backend);
        if (selected == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.UNSUPPORTED_BACKEND,
                    "固定账号群 metadata backend 未注册: " + backend)
                    .withContext(
                            backend,
                            OPERATION,
                            "armada-account:" + account.armadaAccountId());
        }
        return selected.getMetadata(account, groupJid);
    }
}
