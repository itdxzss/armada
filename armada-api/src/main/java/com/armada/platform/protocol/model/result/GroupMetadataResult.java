package com.armada.platform.protocol.model.result;

import java.util.List;

/**
 * 协议层群详情稳定结果。
 *
 * @param groupJid                       群 JID
 * @param subject                        WhatsApp 真实群名
 * @param announce                       是否仅管理员可发言
 * @param restrict                       是否仅管理员可修改群资料
 * @param memberAddMode                  是否所有成员可添加成员
 * @param joinApprovalMode               是否开启入群审批
 * @param ephemeralDurationSeconds       限时消息秒数
 * @param inviteViaLink                  邀请链接入群开关;协议不可读时为 null
 * @param inviteViaLinkSupported         当前协议是否支持该能力
 * @param inviteViaLinkUnsupportedReason 不支持原因
 * @param participants                   群成员
 */
public record GroupMetadataResult(
        String groupJid,
        String subject,
        Boolean announce,
        Boolean restrict,
        Boolean memberAddMode,
        Boolean joinApprovalMode,
        Integer ephemeralDurationSeconds,
        Boolean inviteViaLink,
        boolean inviteViaLinkSupported,
        String inviteViaLinkUnsupportedReason,
        List<GroupParticipantResult> participants
) {
}
