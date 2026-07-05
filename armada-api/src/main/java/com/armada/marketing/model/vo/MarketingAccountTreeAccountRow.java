package com.armada.marketing.model.vo;

/**
 * 营销账号树实时查群前的在线账号候选行。
 *
 * <p>本行只承载本地账号筛选结果和 baseline 元数据,不再携带群列表。群列表统一由服务层实时调用协议层获取。</p>
 */
public class MarketingAccountTreeAccountRow {

    private Long accountId;
    private String wsPhone;
    private String protocolAccountId;
    private Integer groupBaselineState;
    private String baselineGroupJidsJson;

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
}
