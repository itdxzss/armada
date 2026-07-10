package com.armada.marketing.model.vo;

/**
 * 营销账号树账号行。
 *
 * <p>本行承载账号当前状态、baseline 元数据和库内可营销群数量。账号树展示不调用协议层实时查群。</p>
 */
public class MarketingAccountTreeAccountRow {

    private Long accountId;
    private String wsPhone;
    private String protocolAccountId;
    private Integer groupBaselineState;
    private String baselineGroupJidsJson;
    private Integer accountState;
    private Integer loginState;
    private Integer riskStatus;
    private Integer muteStatus;
    private Integer groupCount;

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

    public String getProtocolAccountId() {
        return protocolAccountId;
    }

    public void setProtocolAccountId(String protocolAccountId) {
        this.protocolAccountId = protocolAccountId;
    }

    public Integer getGroupBaselineState() {
        return groupBaselineState;
    }

    public void setGroupBaselineState(Integer groupBaselineState) {
        this.groupBaselineState = groupBaselineState;
    }

    public String getBaselineGroupJidsJson() {
        return baselineGroupJidsJson;
    }

    public void setBaselineGroupJidsJson(String baselineGroupJidsJson) {
        this.baselineGroupJidsJson = baselineGroupJidsJson;
    }

    public Integer getAccountState() {
        return accountState;
    }

    public void setAccountState(Integer accountState) {
        this.accountState = accountState;
    }

    public Integer getLoginState() {
        return loginState;
    }

    public void setLoginState(Integer loginState) {
        this.loginState = loginState;
    }

    public Integer getRiskStatus() {
        return riskStatus;
    }

    public void setRiskStatus(Integer riskStatus) {
        this.riskStatus = riskStatus;
    }

    public Integer getMuteStatus() {
        return muteStatus;
    }

    public void setMuteStatus(Integer muteStatus) {
        this.muteStatus = muteStatus;
    }

    public Integer getGroupCount() {
        return groupCount;
    }

    public void setGroupCount(Integer groupCount) {
        this.groupCount = groupCount;
    }
}
