package com.armada.task.model.vo;

/**
 * 一条已冻结在草稿里的「群链接 ↔ TXT」执行行。
 *
 * @param rowId              执行行 ID，单行移除时回传
 * @param seq                任务内展示与执行顺序
 * @param normalizedLink     归一化群链接
 * @param sourceLinkLineNo   该链接在粘贴文本中的原始行号
 * @param sourceFileName     配对 TXT 的原始文件名
 * @param totalLineCount     TXT 物理行数
 * @param validMemberCount   去重后的有效料子数
 * @param invalidLineCount   非法行数
 * @param duplicateLineCount 文件内重复号码行数
 */
public record PullTaskStandardExecutionRowVO(Long rowId, int seq, String normalizedLink,
                                             int sourceLinkLineNo, String sourceFileName,
                                             int totalLineCount, int validMemberCount,
                                             int invalidLineCount, int duplicateLineCount) {
}
