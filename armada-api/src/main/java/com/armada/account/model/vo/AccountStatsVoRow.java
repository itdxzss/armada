package com.armada.account.model.vo;

/**
 * Mapper 聚合投影:平台级账号统计卡,单条聚合 SQL 结果。
 * 普通类 + getter/setter,供 MyBatis resultType 直接映射(underscore-to-camelCase 自动转换)。
 *
 * <p>列说明:
 * <ul>
 *   <li>total    — 本租户未软删账号总数(COUNT *)</li>
 *   <li>online   — login_state=1(在线)</li>
 *   <li>offline  — account_state IN (1,2,6,7) AND login_state=2(离线)</li>
 *   <li>pendingOnline — login_state=3(待上线)</li>
 *   <li>banned   — account_state=3(封禁)</li>
 *   <li>unbound  — account_state=5(解绑)</li>
 *   <li>muted    — mute_status IS NOT NULL(禁言)</li>
 *   <li>exported — account_state=4(导出)</li>
 *   <li>risk     — risk_status &gt; 1(风控中/待解除)</li>
 *   <li>assigned — dispatched_at IS NOT NULL(已分配/已派单)</li>
 * </ul>
 * unassigned(未分配) = total - assigned,restrictedTotal = banned + unbound + muted + exported,
 * 均由 Service 层派生。
 * </p>
 */
public class AccountStatsVoRow {

    /** 本租户未软删账号总数。 */
    private long total;

    /** login_state=1 在线账号数。 */
    private long online;

    /** account_state IN (1,2,6,7) AND login_state=2 离线账号数。 */
    private long offline;

    /** login_state=3 待上线账号数。 */
    private long pendingOnline;

    /** account_state=3 封禁账号数。 */
    private long banned;

    /** account_state=5 解绑账号数。 */
    private long unbound;

    /** mute_status IS NOT NULL 禁言账号数。 */
    private long muted;

    /** account_state=4 导出账号数。 */
    private long exported;

    /** risk_status&gt;1 风控中/待解除账号数。 */
    private long risk;

    /** dispatched_at IS NOT NULL 已派单账号数。 */
    private long assigned;

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getOnline() {
        return online;
    }

    public void setOnline(long online) {
        this.online = online;
    }

    public long getOffline() {
        return offline;
    }

    public void setOffline(long offline) {
        this.offline = offline;
    }

    public long getPendingOnline() {
        return pendingOnline;
    }

    public void setPendingOnline(long pendingOnline) {
        this.pendingOnline = pendingOnline;
    }

    public long getBanned() {
        return banned;
    }

    public void setBanned(long banned) {
        this.banned = banned;
    }

    public long getUnbound() {
        return unbound;
    }

    public void setUnbound(long unbound) {
        this.unbound = unbound;
    }

    public long getMuted() {
        return muted;
    }

    public void setMuted(long muted) {
        this.muted = muted;
    }

    public long getExported() {
        return exported;
    }

    public void setExported(long exported) {
        this.exported = exported;
    }

    public long getRisk() {
        return risk;
    }

    public void setRisk(long risk) {
        this.risk = risk;
    }

    public long getAssigned() {
        return assigned;
    }

    public void setAssigned(long assigned) {
        this.assigned = assigned;
    }
}
