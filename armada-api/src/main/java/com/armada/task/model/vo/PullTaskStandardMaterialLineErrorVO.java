package com.armada.task.model.vo;

/**
 * TXT 料子文件里的单行失败明细。
 *
 * @param lineNo 文件内原始物理行号
 * @param reason 失败原因，直接展示给运营
 */
public record PullTaskStandardMaterialLineErrorVO(int lineNo, String reason) {
}
