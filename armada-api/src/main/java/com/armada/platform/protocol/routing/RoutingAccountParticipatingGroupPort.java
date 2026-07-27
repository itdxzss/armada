package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.AccountGroupMetadataSummaryResult;
import com.armada.platform.protocol.model.result.AccountParticipatingGroupResult;
import com.armada.platform.protocol.port.AccountParticipatingGroupPort;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 根据固定账号协议后端分发当前参与群读取的统一端口。
 */
public final class RoutingAccountParticipatingGroupPort
        implements AccountParticipatingGroupPort {

    private final Map<ProtocolBackend, AccountParticipatingGroupBackend> backends;

    /**
     * 创建参与群读取路由端口，并拒绝同一协议后端的重复实现。
     *
     * @param candidates 所有固定账号参与群读取后端
     */
    public RoutingAccountParticipatingGroupPort(
            List<AccountParticipatingGroupBackend> candidates) {
        EnumMap<ProtocolBackend, AccountParticipatingGroupBackend> mapped =
                new EnumMap<>(ProtocolBackend.class);
        if (candidates != null) {
            for (AccountParticipatingGroupBackend candidate : candidates) {
                if (candidate == null || candidate.backend() == null) {
                    continue;
                }
                AccountParticipatingGroupBackend previous = mapped.putIfAbsent(
                        candidate.backend(), candidate);
                if (previous != null) {
                    throw new IllegalStateException(
                            "账号参与群 backend 重复注册: " + candidate.backend());
                }
            }
        }
        this.backends = Map.copyOf(mapped);
    }

    @Override
    public List<AccountParticipatingGroupResult.Group> listCurrent(
            ProtocolAccountRef account) {
        return required(account, "account.groups.current").listCurrent(account);
    }

    @Override
    public List<AccountGroupMetadataSummaryResult> summarize(
            ProtocolAccountRef account,
            List<String> groupJids,
            int concurrency) {
        return required(account, "account.groups.metadata-summaries")
                .summarize(account, groupJids, concurrency);
    }

    private AccountParticipatingGroupBackend required(
            ProtocolAccountRef account,
            String operation) {
        if (account == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.BAD_REQUEST,
                    "固定操作账号不能为空");
        }
        ProtocolBackend backend = account.backend();
        AccountParticipatingGroupBackend selected = backends.get(backend);
        if (selected == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.UNSUPPORTED_BACKEND,
                    "账号参与群 backend 未注册: " + backend)
                    .withContext(
                            backend,
                            operation,
                            "armada-account:" + account.armadaAccountId());
        }
        return selected;
    }
}
