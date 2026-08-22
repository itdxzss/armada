package com.armada.account.model.vo;

/**
 * 账号群基线状态行。
 *
 * <p>只承载判定「账号上线后是否需要首次群全量同步」所需的最小字段,不含凭据、代理或
 * 群成员身份。状态为空表示历史数据尚未拍过基线,调用方须按待建立处理；请求水位用于
 * 幂等拦截同一 ONLINE 事件的重复投递。</p>
 *
 * @param accountId          Armada 本地账号 ID
 * @param groupBaselineState 群基线状态码,见 AccountGroupBaselineStateCode
 * @param lastSyncRequestedAt 最近一次账号群同步请求时间(epoch 毫秒),可空
 */
public record AccountGroupBaselineStateRow(
        Long accountId,
        Integer groupBaselineState,
        Long lastSyncRequestedAt
) {
}
