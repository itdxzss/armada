package com.armada.group.model.vo;

import com.armada.group.model.enums.HistoricalGroupMembershipState;
import com.armada.group.model.enums.HistoricalGroupSelfRole;
import com.armada.group.model.enums.RoleCategory;
import com.armada.group.model.enums.SpeechState;

/**
 * 历史群列表中的单群请求级状态。
 *
 * @param groupJid        baseline 中的 WhatsApp 群 JID
 * @param subject         baseline 或本次协议摘要提供的群名;可空
 * @param membershipState 操作账号当前成员关系状态
 * @param roleCategory    自身角色展示分类;未验证、已退出或未知时可空
 * @param selfRole        协议层确认的自身角色;未知时可空
 * @param speechState     当前发言状态;未验证或已退出时可空
 * @param memberSize      当前群成员数;未获取时可空
 * @param announceOnly    当前是否仅管理员可发言;未获取时可空
 * @param errorMessage    本次协议失败的完整错误;无错误时可空
 */
public record HistoricalGroupItemVO(
        String groupJid,
        String subject,
        HistoricalGroupMembershipState membershipState,
        RoleCategory roleCategory,
        HistoricalGroupSelfRole selfRole,
        SpeechState speechState,
        Integer memberSize,
        Boolean announceOnly,
        String errorMessage
) {
}
