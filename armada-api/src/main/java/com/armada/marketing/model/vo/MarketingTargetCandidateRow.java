package com.armada.marketing.model.vo;

/**
 * 建营销任务时从账号、群链接和群预览表拼出来的目标候选行。
 */
public class MarketingTargetCandidateRow {

    private Long accountId;
    private String accountPhone;
    private Long groupLinkId;
    private String groupJid;
    private String groupLinkUrl;
    private String groupName;

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getAccountPhone() {
        return accountPhone;
    }

    public void setAccountPhone(String accountPhone) {
        this.accountPhone = accountPhone;
    }

    public Long getGroupLinkId() {
        return groupLinkId;
    }

    public void setGroupLinkId(Long groupLinkId) {
        this.groupLinkId = groupLinkId;
    }

    public String getGroupJid() {
        return groupJid;
    }

    public void setGroupJid(String groupJid) {
        this.groupJid = groupJid;
    }

    public String getGroupLinkUrl() {
        return groupLinkUrl;
    }

    public void setGroupLinkUrl(String groupLinkUrl) {
        this.groupLinkUrl = groupLinkUrl;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }
}
