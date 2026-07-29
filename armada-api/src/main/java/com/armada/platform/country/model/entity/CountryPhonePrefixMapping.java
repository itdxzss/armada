package com.armada.platform.country.model.entity;

/**
 * 共享国际区号唯一国家映射，映射 country_phone_prefix_mapping 表。
 */
public class CountryPhonePrefixMapping {

    /** 只包含数字的规范化国际区号，例如 1、246。 */
    private String normalizedPrefix;

    /** 共享区号唯一展示的国家/地区 ISO2。 */
    private String countryIso2;

    public String getNormalizedPrefix() {
        return normalizedPrefix;
    }

    public void setNormalizedPrefix(String normalizedPrefix) {
        this.normalizedPrefix = normalizedPrefix;
    }

    public String getCountryIso2() {
        return countryIso2;
    }

    public void setCountryIso2(String countryIso2) {
        this.countryIso2 = countryIso2;
    }
}
