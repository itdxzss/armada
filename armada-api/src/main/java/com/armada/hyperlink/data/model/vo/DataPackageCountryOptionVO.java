package com.armada.hyperlink.data.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 数据包国家筛选候选；UNKNOWN 的 countryIso2 固定为 null。
 *
 * @param value 查询参数值，真实国家为 ISO2
 * @param countryIso2 可空 ISO2 国家码
 * @param nameZh 中文展示名
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DataPackageCountryOptionVO(
        String value,
        String countryIso2,
        String nameZh) {
}
