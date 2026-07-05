package com.armada.account.model.vo;

/**
 * 账号统计卡出参 VO(前端统计卡区域用此结构)。
 *
 * <p>unassigned = total - assigned,restrictedTotal = banned + unbound + muted + exported,
 * 均由 Service 层派生。</p>
 *
 * @param total           本租户未软删账号总数
 * @param online          在线账号数(login_state=1)
 * @param offline         离线账号数(account_state IN (1,2,6,7) AND login_state=2)
 * @param pendingOnline   待上线账号数(login_state=3)
 * @param restrictedTotal 异常账号总计(banned + unbound + muted + exported)
 * @param banned          封禁账号数(account_state=3)
 * @param unbound         解绑账号数(account_state=5)
 * @param muted           禁言账号数(mute_status IS NOT NULL)
 * @param exported        导出账号数(account_state=4)
 * @param risk            风控中/待解除账号数(risk_status&gt;1)
 * @param assigned        已派单账号数(dispatched_at IS NOT NULL)
 * @param unassigned      未派单账号数(total - assigned)
 */
public record AccountStatsVO(
        long total,
        long online,
        long offline,
        long pendingOnline,
        long restrictedTotal,
        long banned,
        long unbound,
        long muted,
        long exported,
        long risk,
        long assigned,
        long unassigned
) {
}
