package com.armada.resource.model.dto;

import com.armada.shared.paging.PageQuery;

/** 拉群数据包Query数据库/查询数据。 */
public class GroupDataPackageQuery extends PageQuery {
    /** 名称关键词。 */
    private String name;
    /** 主要国家。 */
    private String countryIso2;
    /** 大洲。 */
    private String continent;
    /** 使用业务。 */
    private String usageBusiness;
    /** 创建开始毫秒。 */
    private Long createdFrom;
    /** 创建结束毫秒。 */
    private Long createdTo;
    /** 只查可供任务使用的包。 */
    private Boolean forTask;
    /** 读取名称关键词。 */
    public String getName() { return name; }
    /** 设置名称关键词。 */
    public void setName(String value) { name = value; }
    /** 读取主要国家。 */
    public String getCountryIso2() { return countryIso2; }
    /** 设置主要国家。 */
    public void setCountryIso2(String value) { countryIso2 = value; }
    /** 读取大洲。 */
    public String getContinent() { return continent; }
    /** 设置大洲。 */
    public void setContinent(String value) { continent = value; }
    /** 读取使用业务。 */
    public String getUsageBusiness() { return usageBusiness; }
    /** 设置使用业务。 */
    public void setUsageBusiness(String value) { usageBusiness = value; }
    /** 读取创建开始毫秒。 */
    public Long getCreatedFrom() { return createdFrom; }
    /** 设置创建开始毫秒。 */
    public void setCreatedFrom(Long value) { createdFrom = value; }
    /** 读取创建结束毫秒。 */
    public Long getCreatedTo() { return createdTo; }
    /** 设置创建结束毫秒。 */
    public void setCreatedTo(Long value) { createdTo = value; }
    /** 读取只查可供任务使用的包。 */
    public Boolean getForTask() { return forTask; }
    /** 设置只查可供任务使用的包。 */
    public void setForTask(Boolean value) { forTask = value; }
}
