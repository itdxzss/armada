package com.armada.account.model.vo;

/**
 * 账号导入导出的直接文件响应。
 *
 * @param filename    下载文件名
 * @param contentType HTTP Content-Type
 * @param bytes       文件字节
 */
public record AccountImportExportFile(
        String filename,
        String contentType,
        byte[] bytes
) {
}
