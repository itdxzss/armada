package com.armada.resource.model.dto;

import com.armada.shared.paging.PageQuery;

/**
 * IP 数据统计国家/地区维度查询。
 *
 * <p>GET 查询参数通过 {@code @ModelAttribute} 绑定,必须使用可变 class + getter/setter。</p>
 */
public class IpProxyStatsCountryQuery extends PageQuery {

    /** 关键字:匹配国家/地区、网关、用户名或来源。 */
    private String keyword;

    /** 协议码:1=HTTP 2=SOCKS5;为空不筛选。 */
    private Integer protocol;

    /** 来源关键词,模糊匹配;为空不筛选。 */
    private String source;

    /** 资源风险筛选:normal/no_idle/low_available/high_unavailable。 */
    private String risk;

    /** 排序字段白名单由 Mapper XML choose 控制。 */
    private String sortField;

    /** asc/desc;非法值在 SQL 中按 desc 处理。 */
    private String sortOrder;

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public Integer getProtocol() {
        return protocol;
    }

    public void setProtocol(Integer protocol) {
        this.protocol = protocol;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getRisk() {
        return risk;
    }

    public void setRisk(String risk) {
        this.risk = risk;
    }

    public String getSortField() {
        return sortField;
    }

    public void setSortField(String sortField) {
        this.sortField = sortField;
    }

    public String getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(String sortOrder) {
        this.sortOrder = sortOrder;
    }
}
