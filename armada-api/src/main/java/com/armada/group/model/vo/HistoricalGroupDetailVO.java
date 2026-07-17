package com.armada.group.model.vo;

import com.armada.group.model.enums.HistoricalGroupMembershipState;
import com.armada.group.model.enums.HistoricalGroupSelfRole;
import com.armada.group.model.enums.RoleCategory;
import com.armada.group.model.enums.SpeechState;
import java.util.List;

/**
 * 固定操作账号按需读取的单个历史群详情。
 *
 * @param accountId              操作账号 ID
 * @param groupJid               baseline 群 JID
 * @param subject                WhatsApp 当前群名
 * @param membershipState        本次详情读取确认的成员关系状态
 * @param roleCategory           操作账号角色分类
 * @param selfRole               操作账号实时群角色
 * @param speechState            操作账号实时发言状态
 * @param memberSize             实时成员数量
 * @param announceOnly           是否仅管理员可发言
 * @param inviteUrl              当前系统读取的完整邀请链接
 * @param linkAvailable          当前邀请链接是否可用
 * @param operationAllowed       当前详情是否允许成员管理等写操作
 * @param operationDisabledReason 操作被禁用的完整原因
 * @param errorCode              详情读取失败的协议错误码
 * @param errorMessage           详情读取失败的完整错误信息
 * @param members                实时完整成员列表
 */
public record HistoricalGroupDetailVO(
        Long accountId,
        String groupJid,
        String subject,
        HistoricalGroupMembershipState membershipState,
        RoleCategory roleCategory,
        HistoricalGroupSelfRole selfRole,
        SpeechState speechState,
        Integer memberSize,
        Boolean announceOnly,
        String inviteUrl,
        boolean linkAvailable,
        boolean operationAllowed,
        String operationDisabledReason,
        String errorCode,
        String errorMessage,
        List<Member> members) {

    /**
     * 历史群详情中的实时成员与保护状态。
     *
     * @param participantJid        成员完整 WhatsApp JID
     * @param phone                 从成员 JID 提取的完整号码
     * @param self                  是否为当前操作账号本人
     * @param owner                 是否为群主
     * @param admin                 是否为管理员或群主
     * @param selfRole              该成员的实时群角色
     * @param operationAllowed      当前操作账号是否可管理该成员
     * @param operationDisabledReason 该成员不可操作的原因
     */
    public record Member(
            String participantJid,
            String phone,
            boolean self,
            boolean owner,
            boolean admin,
            HistoricalGroupSelfRole selfRole,
            boolean operationAllowed,
            String operationDisabledReason) {
    }
}
