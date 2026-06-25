package com.armada.account.model.dto;

import com.armada.shared.paging.PageQuery;

/**
 * 账号列表查询参数(可变 class extends PageQuery,供 @ModelAttribute 绑定)。
 *
 * <p>所有字段可选;非 null 时 SQL WHERE 追加对应条件(SQL 下推,禁内存分页)。</p>
 */
public class AccountQuery extends PageQuery {

    /** 号码前缀模糊匹配(ws_phone LIKE #{phone}%)。 */
    private String phone;

    /** 账号类型:1个人 2商业(可选)。 */
    private Integer accountType;

    /** 接入协议标识(可选)。 */
    private String protocolId;

    /** 账号状态:1新增 2正常 3封禁 4导出 5解绑(可选;step1 state 全 NULL 天然不命中)。 */
    private Integer accountState;

    /** 风控状态:1未风控 2风控中 3待解除(可选;step1 state 全 NULL 天然不命中)。 */
    private Integer riskStatus;

    /** 归属分组 ID(可选)。 */
    private Long accountGroupId;

    /** 来源:1买量 2裂变 3自购(可选)。 */
    private Integer numberSource;

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Integer getAccountType() {
        return accountType;
    }

    public void setAccountType(Integer accountType) {
        this.accountType = accountType;
    }

    public String getProtocolId() {
        return protocolId;
    }

    public void setProtocolId(String protocolId) {
        this.protocolId = protocolId;
    }

    public Integer getAccountState() {
        return accountState;
    }

    public void setAccountState(Integer accountState) {
        this.accountState = accountState;
    }

    public Integer getRiskStatus() {
        return riskStatus;
    }

    public void setRiskStatus(Integer riskStatus) {
        this.riskStatus = riskStatus;
    }

    public Long getAccountGroupId() {
        return accountGroupId;
    }

    public void setAccountGroupId(Long accountGroupId) {
        this.accountGroupId = accountGroupId;
    }

    public Integer getNumberSource() {
        return numberSource;
    }

    public void setNumberSource(Integer numberSource) {
        this.numberSource = numberSource;
    }
}
