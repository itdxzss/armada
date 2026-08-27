package com.armada.hyperlink.data.service;

import java.util.List;

/**
 * TXT 完整解析后的去重号码和计数。
 *
 * @param uniquePhones 按首次出现顺序排列的合法唯一号码
 * @param totalRows 非空行总数
 * @param invalidRows 格式非法行数
 * @param duplicatedRows 文件内合法重复行数
 */
public record ParsedDataPackagePhones(
        List<String> uniquePhones,
        int totalRows,
        int invalidRows,
        int duplicatedRows) {
}
