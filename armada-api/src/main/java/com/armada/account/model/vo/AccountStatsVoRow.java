package com.armada.account.model.vo;

/**
 * Mapper 聚合投影:平台级账号统计卡,单条聚合 SQL 结果。
 * 普通类 + getter/setter,供 MyBatis resultType 直接映射(underscore-to-camelCase 自动转换)。
 *
 * <p>列说明:
 * <ul>
 *   <li>total    — 本租户未软删账号总数(COUNT *)</li>
 *   <li>online   — login_state=1(在线)</li>
 *   <li>offline  — login_state=2(离线)</li>
 *   <li>banned   — account_state=3(封禁)</li>
 *   <li>risk     — risk_status &gt; 1(风控中/待解除)</li>
 *   <li>assigned — dispatched_at IS NOT NULL(已分配/已派单)</li>
 * </ul>
 * unassigned(未分配) = total - assigned,由 Service 层派生,不在此处。
 * </p>
 */
public class AccountStatsVoRow {

    /** 本租户未软删账号总数。 */
    private long total;

    /** login_state=1 在线账号数。 */
    private long online;

    /** login_state=2 离线账号数。 */
    private long offline;

    /** account_state=3 封禁账号数。 */
    private long banned;

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

    public long getBanned() {
        return banned;
    }

    public void setBanned(long banned) {
        this.banned = banned;
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
