package com.armada.account.model.vo;

/**
 * 账号群基线状态行。
 *
 * <p>只承载判定「协议层上线快照是否需要读取群成员明细」所需的最小字段,不含凭据、代理或
 * 群成员身份。状态为空表示历史数据尚未拍过基线,调用方须按待建立处理。</p>
 *
 * @param accountId          Armada 本地账号 ID
 * @param groupBaselineState 群基线状态码,见 AccountGroupBaselineStateCode
 */
public record AccountGroupBaselineStateRow(
        Long accountId,
        Integer groupBaselineState
) {
}
