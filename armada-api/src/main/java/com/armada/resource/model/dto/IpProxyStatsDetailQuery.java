package com.armada.resource.model.dto;

import com.armada.shared.paging.PageQuery;

/**
 * IP 数据统计国家/地区明细查询。
 */
public class IpProxyStatsDetailQuery extends PageQuery {

    /** 状态码:1=空闲 2=使用中 3=不可用;为空不筛选。 */
    private Integer status;

    /** 协议码:1=HTTP 2=SOCKS5;为空不筛选。 */
    private Integer protocol;

    /** 分配方式:smart=智能分配 mixed=混合分组;为空不筛选。 */
    private String allocationMode;

    /** 来源关键词,模糊匹配;为空不筛选。 */
    private String source;

    /** 关键字:匹配网关、用户名、来源或代理地址。 */
    private String keyword;

    /** IP 地址关键词,匹配代理 host 或 host:port;为空不筛选。 */
    private String ipKeyword;

    /** 当前使用账号关键词,当前按绑定账号 ID 模糊匹配;为空不筛选。 */
    private String accountKeyword;

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getProtocol() {
        return protocol;
    }

    public void setProtocol(Integer protocol) {
        this.protocol = protocol;
    }

    public String getAllocationMode() {
        return allocationMode;
    }

    public void setAllocationMode(String allocationMode) {
        this.allocationMode = allocationMode;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getIpKeyword() {
        return ipKeyword;
    }

    public void setIpKeyword(String ipKeyword) {
        this.ipKeyword = ipKeyword;
    }

    public String getAccountKeyword() {
        return accountKeyword;
    }

    public void setAccountKeyword(String accountKeyword) {
        this.accountKeyword = accountKeyword;
    }
}
