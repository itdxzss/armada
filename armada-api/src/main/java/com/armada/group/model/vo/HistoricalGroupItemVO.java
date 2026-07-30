package com.armada.group.model.vo;

import com.armada.group.model.enums.HistoricalGroupMembershipState;
import com.armada.group.model.enums.HistoricalGroupSelfRole;
import com.armada.group.model.enums.RoleCategory;
import com.armada.group.model.enums.SpeechState;
import java.util.List;

/**
 * 历史群列表中的单群请求级状态。
 *
 * @param groupJid        baseline 中的 WhatsApp 群 JID
 * @param subject         baseline 或本次协议摘要提供的群名;可空
 * @param accountPhones   当前账号组内关联该历史群的账号号码
 * @param inviteLink      当前保存的完整群邀请链接;未知时可空
 * @param countryIso2     群创建者号码识别出的国家 ISO2;未知时可空
 * @param countryName     群创建者号码识别出的国家名称;未知时可空
 * @param countryFlag     群创建者号码识别出的国旗;未知时可空
 * @param groupCreatedAt  WhatsApp 群创建时间,Unix 秒;未知时可空
 * @param membershipState 账号组聚合后的当前成员关系状态
 * @param roleCategory    自身角色展示分类;未验证、已退出或未知时可空
 * @param selfRole        协议层确认的自身角色;未知时可空
 * @param speechState     当前发言状态;未验证或已退出时可空
 * @param memberSize      当前群成员数;未获取时可空
 * @param announceOnly    当前是否仅管理员可发言;未获取时可空
 * @param operable        当前是否存在在线群主/管理员可执行群操作
 * @param disabledReason  当前不可操作原因;可操作时为空
 * @param errorMessage    本次协议失败的完整错误;无错误时可空
 */
public record HistoricalGroupItemVO(
        String groupJid,
        String subject,
        List<String> accountPhones,
        String inviteLink,
        String countryIso2,
        String countryName,
        String countryFlag,
        Long groupCreatedAt,
        HistoricalGroupMembershipState membershipState,
        RoleCategory roleCategory,
        HistoricalGroupSelfRole selfRole,
        SpeechState speechState,
        Integer memberSize,
        Boolean announceOnly,
        boolean operable,
        String disabledReason,
        String errorMessage
) {

    public HistoricalGroupItemVO {
        accountPhones = accountPhones == null ? List.of() : List.copyOf(accountPhones);
    }
}
