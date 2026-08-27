package com.armada.hyperlink.data.model.vo;

/** 当前页数据包与其当前代国家快照的 DISTINCT 投影。 */
public class DataPackageCountryRow {

    /** 数据包 ID。 */
    private Long dataPackageId;
    /** 可空 ISO2 国家码。 */
    private String countryIso2;

    public Long getDataPackageId() { return dataPackageId; }
    public void setDataPackageId(Long dataPackageId) { this.dataPackageId = dataPackageId; }
    public String getCountryIso2() { return countryIso2; }
    public void setCountryIso2(String countryIso2) { this.countryIso2 = countryIso2; }
}
