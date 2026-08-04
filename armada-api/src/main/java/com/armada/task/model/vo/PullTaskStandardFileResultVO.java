package com.armada.task.model.vo;

import java.util.List;

/**
 * 单个 TXT 料子文件的解析结果。
 *
 * @param fileName           原始文件名
 * @param accepted           是否进入随机匹配池；零有效号码的文件为 false
 * @param validMemberCount   去重后的有效号码数
 * @param invalidLineCount   非法行数
 * @param duplicateLineCount 文件内重复号码行数
 * @param rejectReason       未进入匹配池的原因；accepted 为 true 时为 null
 * @param lineErrors         逐行失败明细
 */
public record PullTaskStandardFileResultVO(String fileName, boolean accepted,
                                           int validMemberCount, int invalidLineCount,
                                           int duplicateLineCount, String rejectReason,
                                           List<PullTaskStandardMaterialLineErrorVO> lineErrors) {
}
