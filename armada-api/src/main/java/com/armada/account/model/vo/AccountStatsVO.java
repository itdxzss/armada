package com.armada.account.model.vo;

/**
 * 账号统计卡出参 VO(前端统计卡区域用此结构)。
 *
 * <p>unassigned = total - assigned,由 Service 层派生(不在 Mapper 聚合 SQL 中)。</p>
 *
 * @param total      本租户未软删账号总数
 * @param online     在线账号数(login_state=1)
 * @param offline    离线账号数(login_state=2)
 * @param banned     封禁账号数(account_state=3)
 * @param risk       风控中/待解除账号数(risk_status&gt;1)
 * @param assigned   已派单账号数(dispatched_at IS NOT NULL)
 * @param unassigned 未派单账号数(total - assigned)
 */
public record AccountStatsVO(
        long total,
        long online,
        long offline,
        long banned,
        long risk,
        long assigned,
        long unassigned
) {
}
