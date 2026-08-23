package com.armada.task.model.vo;

/**
 * 一条已冻结在草稿里的 TXT 执行行；拉人模式的群组字段在运行时回填。
 *
 * @param rowId              执行行 ID，单行移除时回传
 * @param seq                任务内展示与执行顺序
 * @param normalizedLink     归一化群链接；资源池草稿中为空
 * @param sourceLinkLineNo   旧粘贴链接行号；资源池草稿中为空
 * @param sourceFileName     TXT 原始文件名
 * @param totalLineCount     TXT 物理行数
 * @param validMemberCount   去重后的有效料子数
 * @param invalidLineCount   非法行数
 * @param duplicateLineCount 文件内重复号码行数
 */
public record PullTaskStandardExecutionRowVO(Long rowId, int seq, String normalizedLink,
                                             Integer sourceLinkLineNo, String sourceFileName,
                                             int totalLineCount, int validMemberCount,
                                             int invalidLineCount, int duplicateLineCount) {
}
