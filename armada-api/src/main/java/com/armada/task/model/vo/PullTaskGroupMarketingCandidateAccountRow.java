package com.armada.task.model.vo;

/** 候选群组下单个可操作账号 SQL 行。 */
public class PullTaskGroupMarketingCandidateAccountRow {

    /** 群入口主键。 */
    private Long groupLinkId;
    /** WhatsApp 群唯一 JID。 */
    private String groupJid;
    /** 平台账号 ID。 */
    private Long accountId;
    /** WhatsApp 账号手机号。 */
    private String accountPhone;
    /** 群内角色：CREATOR 或 ADMIN。 */
    private String groupRole;
    /** 当前登录状态。 */
    private Integer loginState;
    /** 当前账号可用状态。 */
    private Integer accountState;
    /** 群关系最近同步时间。 */
    private Long lastSeenAt;

    public Long getGroupLinkId() { return groupLinkId; }
    public void setGroupLinkId(Long groupLinkId) { this.groupLinkId = groupLinkId; }
    public String getGroupJid() { return groupJid; }
    public void setGroupJid(String groupJid) { this.groupJid = groupJid; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getAccountPhone() { return accountPhone; }
    public void setAccountPhone(String accountPhone) { this.accountPhone = accountPhone; }
    public String getGroupRole() { return groupRole; }
    public void setGroupRole(String groupRole) { this.groupRole = groupRole; }
    public Integer getLoginState() { return loginState; }
    public void setLoginState(Integer loginState) { this.loginState = loginState; }
    public Integer getAccountState() { return accountState; }
    public void setAccountState(Integer accountState) { this.accountState = accountState; }
    public Long getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Long lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
