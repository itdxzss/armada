package com.armada.marketing.model.support;

/**
 * 营销发送结果回填 target 前解析出的群快照。
 *
 * <p>快照通过普通一致性读从 attempt、群预览和群入口解析；后续 target 更新只锁定目标行，
 * 避免营销回执与账号群快照在共享群表上形成交叉锁。</p>
 */
public class MarketingTargetResultSnapshot {

    private Long groupLinkId;
    private String groupJid;
    private String groupLinkUrl;
    private String groupName;

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
