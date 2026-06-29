package com.armada.marketing.model.vo;

/**
 * 营销账号树 Mapper 平铺行:一个账号×一个可营销群。
 *
 * <p>该对象只服务于 MyBatis 查询投影。SQL 负责把账号可用性、群健康状态和账号基线过滤好,
 * Service 再把多行按 {@link #accountId} 聚合成前端需要的账号树。</p>
 */
public class MarketingAccountTreeRow {

    /** 发言账号 ID,来自 account.id。 */
    private Long accountId;

    /** 发言账号 WhatsApp 号码,来自 account.ws_phone。 */
    private String wsPhone;

    /** 群入口 ID,来自 group_link.id,创建任务 selections 提交时会使用该值。 */
    private Long groupLinkId;

    /** WhatsApp 群 JID,来自 group_link_preview.group_jid,协议发送寻址用。 */
    private String groupJid;

    /** 群展示名,优先 group_link.group_name,为空时回退 group_link_preview.wa_subject。 */
    private String groupName;

    /** 群邀请链接,来自 group_link.link_url,用于页面展示和任务目标快照。 */
    private String linkUrl;

    /** 当前账号是否该群管理员,来自 account_group_membership.is_admin。 */
    private Boolean admin;

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getWsPhone() {
        return wsPhone;
    }

    public void setWsPhone(String wsPhone) {
        this.wsPhone = wsPhone;
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

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getLinkUrl() {
        return linkUrl;
    }

    public void setLinkUrl(String linkUrl) {
        this.linkUrl = linkUrl;
    }

    public Boolean getAdmin() {
        return admin;
    }

    public void setAdmin(Boolean admin) {
        this.admin = admin;
    }
}
