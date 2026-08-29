package com.armada.hyperlink.click.model.dto;

/** 单个分析阈值的号码导出请求。 */
public record HyperlinkClickAnalysisExportDTO(
        Long dateFrom,
        Long dateTo,
        Integer threshold,
        String countryIso2,
        String format) {
}
