package com.armada.hyperlink.data.model.dto;

import java.util.List;

/**
 * 批量导出所选数据包点击记录。
 *
 * @param ids 数据包 ID，最多 100 个
 * @param format txt 仅手机号，csv 包含数据包等字段
 */
public record DataPackageClickExportDTO(List<Long> ids, String format) {
}
