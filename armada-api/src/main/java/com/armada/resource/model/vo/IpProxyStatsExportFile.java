package com.armada.resource.model.vo;

/**
 * IP 数据统计导出文件。
 *
 * @param filename    下载文件名
 * @param contentType HTTP Content-Type
 * @param content     文件字节内容
 */
public record IpProxyStatsExportFile(
        String filename,
        String contentType,
        byte[] content) {
}
