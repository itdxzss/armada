package com.armada.hyperlink.data.model.vo;

/**
 * 数据包号码导出文件。
 *
 * @param filename UTF-8 下载文件名
 * @param contentType 响应媒体类型
 * @param bytes 文件内容
 * @param exportedCount 实际导出号码数
 */
public record DataPackageExportFile(
        String filename,
        String contentType,
        byte[] bytes,
        int exportedCount) {
}
