package com.armada.hyperlink.data.model.dto;

import com.armada.shared.paging.PageQuery;
import java.util.List;

/** 数据包列表查询；国家以逗号串绑定，再由 Service 规范化。 */
public class DataPackageQuery extends PageQuery {

    /** 数据包名称模糊筛选。 */
    private String name;
    /** 创建时间下界（epoch 毫秒，包含）。 */
    private Long createdFrom;
    /** 创建时间上界（epoch 毫秒，包含）。 */
    private Long createdTo;
    /** 逗号分隔的 ISO2 或 UNKNOWN 筛选串。 */
    private String countryIso2s;
    /** 是否仅返回可建任务的数据包。 */
    private boolean forTask;
    /** Service 解析后的真实 ISO2 集合。 */
    private List<String> realCountryIso2s = List.of();
    /** 是否包含未识别国家。 */
    private boolean includeUnknownCountry;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getCreatedFrom() { return createdFrom; }
    public void setCreatedFrom(Long createdFrom) { this.createdFrom = createdFrom; }
    public Long getCreatedTo() { return createdTo; }
    public void setCreatedTo(Long createdTo) { this.createdTo = createdTo; }
    public String getCountryIso2s() { return countryIso2s; }
    public void setCountryIso2s(String countryIso2s) { this.countryIso2s = countryIso2s; }
    public boolean isForTask() { return forTask; }
    public void setForTask(boolean forTask) { this.forTask = forTask; }
    public List<String> getRealCountryIso2s() { return realCountryIso2s; }
    public void setRealCountryIso2s(List<String> realCountryIso2s) {
        this.realCountryIso2s = realCountryIso2s == null ? List.of() : List.copyOf(realCountryIso2s);
    }
    public boolean isIncludeUnknownCountry() { return includeUnknownCountry; }
    public void setIncludeUnknownCountry(boolean includeUnknownCountry) {
        this.includeUnknownCountry = includeUnknownCountry;
    }
    public boolean isCountryFiltered() {
        return includeUnknownCountry || !realCountryIso2s.isEmpty();
    }
}
