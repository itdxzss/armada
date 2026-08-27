package com.armada.hyperlink.data.model.vo;

import com.armada.hyperlink.data.model.enums.DataPackagePoolStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 数据包当前代号码明细。
 *
 * @param id 号码行 ID
 * @param generation 所属代次
 * @param phone 纯数字国际号码
 * @param countryIso2 可空 ISO2 国家码
 * @param poolStatus 当前互斥池状态
 * @param sourceImportId 来源导入审计 ID
 * @param createdAt 创建时间（epoch 毫秒）
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DataPackagePhoneItemVO(
        Long id,
        int generation,
        String phone,
        String countryIso2,
        DataPackagePoolStatus poolStatus,
        Long sourceImportId,
        long createdAt) {
}
