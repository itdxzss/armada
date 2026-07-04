package com.armada.group.model.vo;

/**
 * 单条群链接导入前预检测结果。
 *
 * @param lineNo        原始输入行号,从 1 开始;空行不返回
 * @param rawUrl        原始行文本(trim 后)
 * @param normalizedUrl 归一化群邀请链接
 * @param inviteCode    群邀请 code
 * @param groupName     WhatsApp 公开邀请页识别出的群名称
 * @param avatarUrl     WhatsApp 公开邀请页识别出的真实群头像 URL
 * @param status        预检测状态:AVAILABLE/UNAVAILABLE
 * @param statusLabel   预检测状态中文展示
 * @param failReason    不可用原因;可用时为空
 */
public record GroupLinkPrecheckItemVO(
        int lineNo,
        String rawUrl,
        String normalizedUrl,
        String inviteCode,
        String groupName,
        String avatarUrl,
        String status,
        String statusLabel,
        String failReason) {
}
