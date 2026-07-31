package com.armada.marketing.export.model.dto;

import java.util.List;

/** 普通营销任务异步导出请求。 */
public record MarketingTaskExportRequestDTO(
        String exportMode,
        List<Long> taskIds,
        List<String> countryIso2s) {
}
