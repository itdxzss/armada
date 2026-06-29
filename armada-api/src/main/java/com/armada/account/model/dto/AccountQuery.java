package com.armada.account.model.dto;

import com.armada.shared.paging.PageQuery;

/**
 * 账号列表查询参数(可变 class extends PageQuery,供 @ModelAttribute 绑定)。
 *
 * <p>所有字段可选;非 null 时 SQL WHERE 追加对应条件(SQL 下推,禁内存分页)。</p>
 */
public class AccountQuery extends PageQuery {

    /** 顶部搜索:账号前缀或备注模糊匹配。 */
    private String keyword;

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

    /** 登录状态:1在线 2离线(可选)。 */
    private Integer loginState;

    /** 禁言状态:1禁言6h 2禁言24h(可选)。 */
    private Integer muteStatus;

    /** 归属分组 ID(可选)。 */
    private Long accountGroupId;

    /** 来源:1买量 2裂变 3自购(可选)。 */
    private Integer numberSource;

    /** 推广渠道名(可选,模糊匹配)。 */
    private String channelName;

    /** IP 国家/出口国家(可选,模糊匹配状态回写国家或当前绑定代理国家)。 */
    private String country;

    /** 真实出口 IP(可选,模糊匹配状态回写 IP 或当前绑定代理地址)。 */
    private String truthIp;

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

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

    public Integer getLoginState() {
        return loginState;
    }

    public void setLoginState(Integer loginState) {
        this.loginState = loginState;
    }

    public Integer getMuteStatus() {
        return muteStatus;
    }

    public void setMuteStatus(Integer muteStatus) {
        this.muteStatus = muteStatus;
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

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getTruthIp() {
        return truthIp;
    }

    public void setTruthIp(String truthIp) {
        this.truthIp = truthIp;
    }
}
