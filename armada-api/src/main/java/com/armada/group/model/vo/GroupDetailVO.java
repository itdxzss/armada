package com.armada.group.model.vo;

import java.util.List;

/**
 * 群详情抽屉聚合结果。
 *
 * @param groupLinkId               群链接 ID
 * @param groupJid                  WhatsApp 群 JID
 * @param groupName                 最后成功快照群名;不可用时回退本地群名
 * @param remark                    Armada 本地备注
 * @param avatarUrl                 已持久化的最近群头像
 * @param liveStateAvailable        完整 metadata 快照是否可用（兼容旧字段名）
 * @param liveStateUnavailableReason metadata 快照不可用原因
 * @param timedMessageMode          限时消息模式 wire 值
 * @param permissions               最后成功快照中的群权限
 * @param capabilities              当前详情读取可表达的协议能力声明
 * @param membersAvailable          完整成员快照是否可用
 * @param membersUnavailableReason  成员列表不可用原因
 * @param members                   最后一次完整成员快照
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
        List<GroupLinkMemberVO> members,
        String metadataSyncStatus,
        Long metadataSyncedAt,
        String metadataSyncError
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
