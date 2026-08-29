package com.armada.hyperlink.data.model.dto;

/** 完整更新数据包元数据的请求。 */
public record DataPackageUpdateDTO(
        /** 数据包名称。 */ String name,
        /** 可空备注。 */ String remark,
        /** 必填乐观锁版本。 */ Integer version) {
}
