package com.armada.marketing.model.vo;

/**
 * 建群营销执行账号候选投影。
 *
 * <p>由账号表和账号状态表联查生成，既承载建群营销选择、替换账号所需的可用性状态，
 * 也携带协议路由所需的当前账号事实。业务 Worker 只使用这里的路由事实，
 * 不根据手机号推导协议账号或判断具体协议实现。</p>
 */
public class GroupCreationMarketingAccountCandidate {

    /** Armada 业务账号 ID。 */
    private Long accountId;

    /** WhatsApp 登录号码，对应账号表 {@code ws_phone}。 */
    private String accountPhone;

    /** 协议层账号 ID，用于协议命令和结果事件关联。 */
    private String protocolAccountId;

    /** 协议实现 ID，由统一消息路由选择对应 Web/Android backend。 */
    private String protocolId;

    /** 账号当前机器状态。 */
    private Integer accountState;

    /** 账号当前登录状态。 */
    private Integer loginState;

    /** 账号当前风控状态。 */
    private Integer riskStatus;

    /** 账号当前禁言状态；为空表示未禁言。 */
    private Integer muteStatus;

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

    public String getProtocolAccountId() {
        return protocolAccountId;
    }

    public void setProtocolAccountId(String protocolAccountId) {
        this.protocolAccountId = protocolAccountId;
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
}
