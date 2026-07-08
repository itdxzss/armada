package com.armada.marketing.model.vo;

/**
 * 建群营销导出文件结果。
 *
 * @param filename    下载文件名
 * @param contentType HTTP content-type
 * @param bytes       Excel 文件二进制内容
 */
public record GroupCreationMarketingExportFile(
        String filename,
        String contentType,
        byte[] bytes
) {
}
