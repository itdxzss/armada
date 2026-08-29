package com.armada.hyperlink.data.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 数据包分页列表项。
 *
 * @param id 数据包 ID
 * @param name 数据包名称
 * @param remark 可空备注
 * @param countries 当前代国家快照，未知国家为 null
 * @param primaryCountryIso2 当前代号码数量最多的主要国家，未识别或空包为 null
 * @param metrics 当前代统计指标
 * @param version 元数据乐观锁版本
 * @param createdAt 创建时间（epoch 毫秒）
 * @param updatedAt 更新时间（epoch 毫秒）
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DataPackageListItemVO(
        Long id,
        String name,
        String remark,
        List<String> countries,
        String primaryCountryIso2,
        DataPackageMetricsVO metrics,
        int version,
        long createdAt,
        long updatedAt) {
}
