package com.armada.hyperlink.click.model.dto;

/** 点击分析查询参数；时间为 epoch 毫秒。 */
public class HyperlinkClickAnalysisQuery {

    private Long dateFrom;
    private Long dateTo;
    private String thresholds;
    private String dimension;
    private String countryIso2;

    public Long getDateFrom() { return dateFrom; }
    public void setDateFrom(Long dateFrom) { this.dateFrom = dateFrom; }
    public Long getDateTo() { return dateTo; }
    public void setDateTo(Long dateTo) { this.dateTo = dateTo; }
    public String getThresholds() { return thresholds; }
    public void setThresholds(String thresholds) { this.thresholds = thresholds; }
    public String getDimension() { return dimension; }
    public void setDimension(String dimension) { this.dimension = dimension; }
    public String getCountryIso2() { return countryIso2; }
    public void setCountryIso2(String countryIso2) { this.countryIso2 = countryIso2; }
}
