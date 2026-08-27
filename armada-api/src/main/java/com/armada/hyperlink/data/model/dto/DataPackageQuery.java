package com.armada.hyperlink.data.model.dto;

import com.armada.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.util.ArrayList;
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
    /** 点击 UV 占发送成功数的最小百分比。 */
    private BigDecimal minUvPercent;
    /** 点击 UV 占发送成功数的最大百分比。 */
    private BigDecimal maxUvPercent;
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
    public BigDecimal getMinUvPercent() { return minUvPercent; }
    public void setMinUvPercent(BigDecimal minUvPercent) { this.minUvPercent = minUvPercent; }
    public BigDecimal getMaxUvPercent() { return maxUvPercent; }
    public void setMaxUvPercent(BigDecimal maxUvPercent) { this.maxUvPercent = maxUvPercent; }
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
    public List<String> getCountryValues() {
        if (!includeUnknownCountry) {
            return realCountryIso2s;
        }
        ArrayList<String> values = new ArrayList<>(realCountryIso2s);
        values.add("UNKNOWN");
        return List.copyOf(values);
    }
}
