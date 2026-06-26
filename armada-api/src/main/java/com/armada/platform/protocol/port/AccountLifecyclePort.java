package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.command.OnlineCommand;
import com.armada.platform.protocol.model.result.OnlineAccepted;

/**
 * 账号生命周期协议端口(防腐层对外接口)。
 *
 * <p>业务域通过本端口下发账号生命周期命令,看不到 HTTP/wire 细节——换传输或换协议层实现时业务零改。
 * 本端口对应协议层 /v1/accounts/{id}/online|offline|logout|reconnect|status|probe 能力族;
 * 首期(①口)只声明 online,其余方法随后续口增量补。</p>
 *
 * <p>实现失败抛 {@code ProtocolException}(运行时,②口建);调用方按其错误码
 * (超时 / 限流 / PROXY_REQUIRED / NOT_OWNER 等)决定重试或退避。</p>
 */
public interface AccountLifecyclePort {

    /**
     * 账号上线(load+connect):把 armada 自托管的 creds + 出口代理喂给协议层,协议层当场 load 并发起握手。
     *
     * <p>异步语义:返回的 {@link OnlineAccepted#accepted()}=true 仅表示协议层已受理并开始处理,
     * 真正 ONLINE 状态由 Kafka account.state_changed 异步回写(②口),不可据本回执判定上线成功。</p>
     *
     * @param protocolAccountId 协议层账号句柄(acc_&lt;wsPhone&gt;)
     * @param command           上线命令(凭据格式 + 凭据 blob + 出口代理)
     * @return 协议层"已受理"回执(含归属路由)
     */
    OnlineAccepted online(String protocolAccountId, OnlineCommand command);
}
