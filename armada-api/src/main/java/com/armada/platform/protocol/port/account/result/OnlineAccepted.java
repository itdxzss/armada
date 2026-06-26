package com.armada.platform.protocol.port.account.result;

import java.time.Instant;

/**
 * 账号上线"已受理"回执(防腐层语义)。
 *
 * <p>对应协议层 online 的 202 响应。{@code accepted=true} 仅表示协议层已开始处理(发起握手),
 * <b>不等于已 ONLINE</b>;真正的 ONLINE 终态通过 Kafka account.state_changed 异步回写(②口)。
 * 业务编排拿到本回执只应落"已受理/等待终态"意图,不得据此判定上线成功。</p>
 *
 * @param protocolAccountId 协议层账号句柄(acc_&lt;wsPhone&gt;);adapter 从 wire accountId 映射而来
 * @param accepted          协议层是否已受理并发起握手
 * @param stateSource       状态来源,供审计/落库 state_source
 * @param syncedAt          协议层受理时刻(由 ISO8601 解析而来)
 * @param routing           归属路由信息
 */
public record OnlineAccepted(
        String protocolAccountId,
        boolean accepted,
        StateSource stateSource,
        Instant syncedAt,
        OnlineRouting routing
) {
}
