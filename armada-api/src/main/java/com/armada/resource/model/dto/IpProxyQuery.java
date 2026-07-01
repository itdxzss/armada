package com.armada.resource.model.dto;

import com.armada.shared.paging.PageQuery;

/**
 * IP 代理列表查询。GET 列表用 {@code @ModelAttribute} 绑定，故为可变 class extends PageQuery。
 *
 * <p>对应需求 §10 搜索项：来源（模糊）、国家（按 region 中文筛选）、类型（协议码）、关键字（组合）。</p>
 */
public class IpProxyQuery extends PageQuery {

    /** 国家/分组中文展示名筛选；为空/「全部」不参与。 */
    private String region;

    /** 新国家下拉提交值:真实国家为 ISO/CLDR 二字母码,混合为 MIXED；为空时兼容旧 region。 */
    private String countryValue;

    /** 协议码（1=HTTP 2=SOCKETS）；为空不参与。 */
    private Integer protocol;

    /** 来源关键词，模糊匹配；为空不参与。 */
    private String source;

    /** 组合关键词（网关/用户名/来源/国家模糊）；为空不参与。 */
    private String keyword;

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getCountryValue() {
        return countryValue;
    }

    public void setCountryValue(String countryValue) {
        this.countryValue = countryValue;
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

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }
}
