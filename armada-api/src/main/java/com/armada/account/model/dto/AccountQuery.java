package com.armada.account.model.dto;

import com.armada.shared.paging.PageQuery;
import com.armada.shared.security.DataScope;
import java.util.List;

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

    /** 账号状态:1新增 2正常 3封禁 4导出 5解绑 6被抢登 7抢登中 8账号受限(可选;step1 state 全 NULL 天然不命中)。 */
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

    /**
     * 营销占用展示类型：FREE、各营销业务类型、PAUSED 或 RELEASING。
     */
    private String marketingOccupancyType;

    /** 占用任务 ID 或任务名称关键词。 */
    private String occupiedTaskKeyword;

    /** 占用任务业务类型：1单纯营销，2拉群营销。 */
    private Integer occupiedBusinessType;

    /** 可调用状态：true 可调用，false 不可调用。 */
    private Boolean callable;

    /**
     * Service 根据营销高级筛选预先解析的分组 ID；不直接采信接口绑定值。
     */
    private List<Long> resolvedOccupancyGroupIds;

    /** 服务端解析的数据范围；不参与 HTTP 绑定。 */
    private DataScope dataScope;

    public DataScope getDataScope() {
        return dataScope;
    }

    /** 仅供 Service 注入服务端范围，避免被 Spring ModelAttribute 绑定。 */
    public void applyDataScope(DataScope dataScope) {
        this.dataScope = dataScope;
    }

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

    public String getMarketingOccupancyType() {
        return marketingOccupancyType;
    }

    public void setMarketingOccupancyType(String marketingOccupancyType) {
        this.marketingOccupancyType = marketingOccupancyType;
    }

    public String getOccupiedTaskKeyword() {
        return occupiedTaskKeyword;
    }

    public void setOccupiedTaskKeyword(String occupiedTaskKeyword) {
        this.occupiedTaskKeyword = occupiedTaskKeyword;
    }

    public Integer getOccupiedBusinessType() {
        return occupiedBusinessType;
    }

    public void setOccupiedBusinessType(Integer occupiedBusinessType) {
        this.occupiedBusinessType = occupiedBusinessType;
    }

    public Boolean getCallable() {
        return callable;
    }

    public void setCallable(Boolean callable) {
        this.callable = callable;
    }

    public List<Long> getResolvedOccupancyGroupIds() {
        return resolvedOccupancyGroupIds;
    }

    public void setResolvedOccupancyGroupIds(List<Long> resolvedOccupancyGroupIds) {
        this.resolvedOccupancyGroupIds = resolvedOccupancyGroupIds;
    }
}
