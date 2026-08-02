package com.armada.platform.kafka.consumer.account;

import java.util.List;

/**
 * 协议账号当前群列表 Kafka 事件。
 *
 * <p>顶层 {@code accountId} 对应 Armada 的 {@code protocol_account_id};
 * {@code tenantId/accountId/groups} 来自 envelope.data。</p>
 *
 * @param eventId           协议层事件 ID,用于日志排查和后续幂等
 * @param tenantId          租户 ID
 * @param accountId         Armada 本地账号 ID
 * @param protocolAccountId 协议账号句柄
 * @param reportedAt        协议层同步时间(epoch 毫秒)
 * @param source            群列表同步来源
 * @param snapshotComplete  协议层是否确认快照完整
 * @param skippedGroupCount 协议层过滤的异常群数量
 * @param workerId          产生事件的协议层 worker ID
 * @param groups            该账号当前参与的群列表
 */
public record ProtocolAccountGroupsReportedEvent(
        String eventId,
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        Long reportedAt,
        String source,
        Boolean snapshotComplete,
        Integer skippedGroupCount,
        String workerId,
        List<Group> groups
) {

    /**
     * 账号当前参与的单个群。
     *
     * @param groupJid     WhatsApp 群 JID
     * @param subject      群名称,可空
     * @param memberCount  群人数,可空
     * @param ownerJid     群主 JID,可空
     * @param ownerPhone   群主号码,可空
     * @param admin        当前账号是否管理员,可空
     * @param announceOnly 是否仅管理员发言,可空
     * @param avatarUrl    群头像 URL,可空
     * @param creation     WhatsApp 群创建时间,Unix 秒;可空
     * @param participants WhatsApp 当前成员快照；null 表示旧协议未上报
     * @param participantsComplete 协议是否明确确认 participant 列表完整
     */
    public record Group(
            String groupJid,
            String subject,
            Integer memberCount,
            String ownerJid,
            String ownerPhone,
            Boolean admin,
            Boolean announceOnly,
            String avatarUrl,
            Long creation,
            List<Participant> participants,
            Boolean participantsComplete
    ) {

        /** 兼容旧协议事件不带 creation 的构造方式。 */
        public Group(
                String groupJid,
                String subject,
                Integer memberCount,
                String ownerJid,
                String ownerPhone,
                Boolean admin,
                Boolean announceOnly,
                String avatarUrl) {
            this(groupJid, subject, memberCount, ownerJid, ownerPhone,
                    admin, announceOnly, avatarUrl, null, null, null);
        }

        /** 兼容尚未上报 participant 明细的调用方。 */
        public Group(
                String groupJid,
                String subject,
                Integer memberCount,
                String ownerJid,
                String ownerPhone,
                Boolean admin,
                Boolean announceOnly,
                String avatarUrl,
                Long creation) {
            this(groupJid, subject, memberCount, ownerJid, ownerPhone,
                    admin, announceOnly, avatarUrl, creation, null, null);
        }

        /** 兼容尚未携带 participant 完整性标记的调用方。 */
        public Group(
                String groupJid,
                String subject,
                Integer memberCount,
                String ownerJid,
                String ownerPhone,
                Boolean admin,
                Boolean announceOnly,
                String avatarUrl,
                Long creation,
                List<Participant> participants) {
            this(groupJid, subject, memberCount, ownerJid, ownerPhone,
                    admin, announceOnly, avatarUrl, creation, participants, null);
        }
    }

    /** WhatsApp 当前群成员身份与角色。 */
    public record Participant(
            String memberJid,
            String jid,
            String phone,
            String role,
            Boolean admin,
            Boolean owner) {
    }
}
