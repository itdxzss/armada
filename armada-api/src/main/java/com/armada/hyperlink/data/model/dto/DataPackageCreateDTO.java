package com.armada.hyperlink.data.model.dto;

/** 创建数据包请求。 */
public record DataPackageCreateDTO(
        /** 数据包名称。 */ String name,
        /** 可空备注。 */ String remark) {
}
