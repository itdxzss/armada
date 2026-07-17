package com.armada.group.model.vo;

import java.util.List;

/**
 * 群详情抽屉聚合结果。
 *
 * @param groupLinkId               群链接 ID
 * @param groupJid                  WhatsApp 群 JID
 * @param groupName                 实时群名;不可用时回退本地群名
 * @param remark                    Armada 本地备注
 * @param avatarUrl                 已持久化的最近群头像
 * @param liveStateAvailable        实时群状态是否可用
 * @param liveStateUnavailableReason 实时状态不可用原因
 * @param timedMessageMode          限时消息模式 wire 值
 * @param permissions               实时群权限
 * @param capabilities              协议能力声明
 * @param membersAvailable          实时成员列表是否可用
 * @param membersUnavailableReason  成员列表不可用原因
 * @param members                   实时成员列表
 */
public record GroupDetailVO(
        Long groupLinkId,
        String groupJid,
        String groupName,
        String remark,
        String avatarUrl,
        boolean liveStateAvailable,
        String liveStateUnavailableReason,
        String timedMessageMode,
        Permissions permissions,
        Capabilities capabilities,
        boolean membersAvailable,
        String membersUnavailableReason,
        List<GroupLinkMemberVO> members
) {

    /** 群开关的实时权限状态;null 表示未知。 */
    public record Permissions(
            Boolean editGroupSettings,
            Boolean sendMessages,
            Boolean addMembers,
            Boolean inviteViaLink,
            Boolean adminApproveNewMembers
    ) {
    }

    /** 当前协议能力集。 */
    public record Capabilities(Capability inviteViaLink) {
    }

    /** 单项协议能力及不支持原因。 */
    public record Capability(boolean supported, String reason) {
    }
}
