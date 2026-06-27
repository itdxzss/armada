package com.armada.platform.protocol.model.command;

import java.util.List;

/**
 * 批量账号上线命令。
 *
 * <p>这是 armada 防腐层内部模型,对应协议层 {@code /v1/accounts/online/batch}。
 * HTTP 只代表命令投递与受理,不代表账号最终 ONLINE。</p>
 *
 * @param items     本批需要上线的账号命令,账号 ID 使用协议层账号句柄
 * @param maxWaitMs 协议层等待上线令牌的最长时间,单位毫秒
 */
public record BatchOnlineCommand(
        List<BatchOnlineCommandItem> items,
        int maxWaitMs
) {
}
