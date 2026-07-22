package com.armada.group.model.vo;

/**
 * 账号群 baseline 状态行,供异步群回报入口判断是否允许写当前 membership。
 *
 * <p>群名映射仅是首次快照的静态展示信息,历史范围仍只以群 JID 数组为准。</p>
 */
public class AccountGroupBaselineRow {

    private Long accountId;
    private String protocolId;
    private String protocolAccountId;
    private Integer groupBaselineState;
    private String baselineGroupJidsJson;

    /** 首次 baseline 中 JID 到静态群名的 JSON 映射;不表达当前成员关系。 */
    private String baselineGroupSubjectsJson;

    /** 首次 baseline 中去重后的群 JID 数量。 */
    private Integer groupCount;

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getProtocolId() {
        return protocolId;
    }

    public void setProtocolId(String protocolId) {
        this.protocolId = protocolId;
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

    /**
     * 返回首次 baseline 中 JID 到静态群名的 JSON 映射。
     *
     * @return 群名 JSON;历史行可为 null
     */
    public String getBaselineGroupSubjectsJson() {
        return baselineGroupSubjectsJson;
    }

    /**
     * 设置首次 baseline 中 JID 到静态群名的 JSON 映射。
     *
     * @param baselineGroupSubjectsJson 群名 JSON;不表达当前成员关系
     */
    public void setBaselineGroupSubjectsJson(String baselineGroupSubjectsJson) {
        this.baselineGroupSubjectsJson = baselineGroupSubjectsJson;
    }

    /**
     * 返回首次 baseline 的去重 JID 数量。
     *
     * @return 群数量
     */
    public Integer getGroupCount() {
        return groupCount;
    }

    /**
     * 设置首次 baseline 的去重 JID 数量。
     *
     * @param groupCount 群数量
     */
    public void setGroupCount(Integer groupCount) {
        this.groupCount = groupCount;
    }
}
