package com.armada.hyperlink.data.model.dto;

import java.util.List;

/**
 * 批量导出数据包号码请求。
 *
 * @param ids 数据包 ID，最多 100 个
 * @param usageStatus 号码使用状态口径
 */
public record DataPackageBatchExportDTO(List<Long> ids, String usageStatus) {
}
