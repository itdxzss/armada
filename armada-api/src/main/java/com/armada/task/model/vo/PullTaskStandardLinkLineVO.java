package com.armada.task.model.vo;

import com.armada.task.model.enums.PullTaskStandardLinkLineStatus;

/**
 * 粘贴文本里单行群链接的判定结果。
 *
 * @param lineNo         原始物理行号
 * @param raw            trim 后的行原文
 * @param normalizedLink 归一化链接；格式非法时为 null
 * @param status         判定终态
 * @param reason         失败或提示原因；无需提示时为 null
 */
public record PullTaskStandardLinkLineVO(int lineNo, String raw, String normalizedLink,
                                         PullTaskStandardLinkLineStatus status, String reason) {
}
