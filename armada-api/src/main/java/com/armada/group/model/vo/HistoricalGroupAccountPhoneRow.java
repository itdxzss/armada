package com.armada.group.model.vo;

/** 历史群关联账号号码查询行。 */
public class HistoricalGroupAccountPhoneRow {

    private String groupJid;
    private String accountPhone;
    private Boolean currentRelation;

    /** @return WhatsApp 群 JID */
    public String getGroupJid() {
        return groupJid;
    }

    /** @param groupJid WhatsApp 群 JID */
    public void setGroupJid(String groupJid) {
        this.groupJid = groupJid;
    }

    /** @return 关联账号号码 */
    public String getAccountPhone() {
        return accountPhone;
    }

    /** @param accountPhone 关联账号号码 */
    public void setAccountPhone(String accountPhone) {
        this.accountPhone = accountPhone;
    }

    /** @return 是否为当前真实在群关系 */
    public Boolean getCurrentRelation() {
        return currentRelation;
    }

    /** @param currentRelation 是否为当前真实在群关系 */
    public void setCurrentRelation(Boolean currentRelation) {
        this.currentRelation = currentRelation;
    }
}
