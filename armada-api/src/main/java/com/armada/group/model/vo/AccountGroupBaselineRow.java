package com.armada.group.model.vo;

/** 账号群 baseline 状态行,供异步群回报入口判断是否允许写当前 membership。 */
public class AccountGroupBaselineRow {

    private Long accountId;
    private Integer groupBaselineState;
    private String baselineGroupJidsJson;

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
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
