package com.armada.platform.country.model.entity;

/**
 * 手机号国内号段到原始分配归属区的映射，映射 country_phone_region_prefix_mapping 表。
 *
 * <p>该归属区只表示号码号段的原始分配区域，不表示持有人当前所在地。</p>
 */
public class CountryPhoneRegionPrefixMapping {

    /** 国家/地区 ISO2。 */
    private String countryIso2;

    /** 去掉国际区号后的纯数字国内号段前缀。 */
    private String normalizedNationalPrefix;

    /** 数据源中的归属区稳定代码。 */
    private String regionCode;

    /** 归属区中文展示名。 */
    private String regionNameZh;

    public String getCountryIso2() {
        return countryIso2;
    }

    public void setCountryIso2(String countryIso2) {
        this.countryIso2 = countryIso2;
    }

    public String getNormalizedNationalPrefix() {
        return normalizedNationalPrefix;
    }

    public void setNormalizedNationalPrefix(String normalizedNationalPrefix) {
        this.normalizedNationalPrefix = normalizedNationalPrefix;
    }

    public String getRegionCode() {
        return regionCode;
    }

    public void setRegionCode(String regionCode) {
        this.regionCode = regionCode;
    }

    public String getRegionNameZh() {
        return regionNameZh;
    }

    public void setRegionNameZh(String regionNameZh) {
        this.regionNameZh = regionNameZh;
    }
}
