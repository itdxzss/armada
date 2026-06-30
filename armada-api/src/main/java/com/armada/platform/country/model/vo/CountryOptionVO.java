package com.armada.platform.country.model.vo;

/**
 * 国家下拉选项。value 是前端提交值:真实国家用 iso2,混合虚拟项用 MIXED。
 */
public record CountryOptionVO(
        String value,
        String iso2,
        String nameZh,
        String phonePrefix,
        String flag,
        boolean virtual) {
}
