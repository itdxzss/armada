package com.armada.platform.country.model.vo;

/**
 * 跨业务域使用的国家主数据只读引用，避免调用方直接依赖 CountryMapper 或 Country 实体。
 *
 * @param id 国家主键
 * @param iso2 ISO/CLDR 二字母国家码
 * @param nameZh 中文展示名称
 * @param phonePrefix 手机号国际区号
 * @param flag 国旗 emoji
 * @param continentCode 六大洲代码；特殊南极地区可空
 */
public record CountryReferenceVO(
        Long id,
        String iso2,
        String nameZh,
        String phonePrefix,
        String flag,
        String continentCode) {
}
