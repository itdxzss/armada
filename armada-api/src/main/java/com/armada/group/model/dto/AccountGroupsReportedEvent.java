package com.armada.group.model.dto;

import java.util.List;

/**
 * 账号当前群列表回报事件。
 *
 * @param tenantId          租户 ID
 * @param accountId         Armada 本地账号 ID
 * @param protocolAccountId 协议账号句柄,仅用于日志
 * @param reportedAt        协议层同步时间(epoch 毫秒),不可空
 * @param groups            协议层返回的账号当前参与群列表
 * @param eventId           协议层事件 ID,仅用于日志
 * @param source            群列表同步来源,仅用于日志
 * @param snapshotComplete  协议层是否确认本次快照完整
 * @param skippedGroupCount 协议层过滤的异常群条目数量
 */
public record AccountGroupsReportedEvent(
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        Long reportedAt,
        List<Group> groups,
        String eventId,
        String source,
        Boolean snapshotComplete,
        Integer skippedGroupCount
) {

    /**
     * 兼容不带同步来源的内部调用。
     */
    public AccountGroupsReportedEvent(
            Long tenantId,
            Long accountId,
            String protocolAccountId,
            Long reportedAt,
            List<Group> groups,
            String eventId) {
        this(tenantId, accountId, protocolAccountId, reportedAt, groups, eventId, null);
    }

    /** 兼容尚未携带快照完整性字段的内部和 Web 协议调用。 */
    public AccountGroupsReportedEvent(
            Long tenantId,
            Long accountId,
            String protocolAccountId,
            Long reportedAt,
            List<Group> groups,
            String eventId,
            String source) {
        this(tenantId, accountId, protocolAccountId, reportedAt, groups, eventId, source, null, null);
    }

    /**
     * 账号当前参与的单个群。
     *
     * <p>群设置字段一律可空,null 表示协议本次未观察到,落库时保留已知事实;
     * 明确的 {@code false} 必须与 null 区分并真正落库。</p>
     *
     * @param groupJid       WhatsApp 群 JID
     * @param subject        群名称,可空
     * @param memberCount    群人数,可空
     * @param ownerJid       群主 JID,可空
     * @param ownerPhone     群主号码,可空
     * @param admin          当前账号是否管理员,可空
     * @param announceOnly   是否仅管理员发言,可空
     * @param avatarUrl      群头像 URL,可空
     * @param groupCreatedAt WhatsApp 群创建时间,Unix 秒;可空
     * @param adminOnlyEditInfo 是否仅管理员可编辑群资料,可空
     * @param memberAddMode  普通成员是否可添加成员,可空
     * @param description    群描述,可空;是否采纳由 descriptionObserved 决定
     * @param descriptionObserved 本次是否观察到群描述字段
     * @param joinApprovalMode 是否开启入群审批,可空
     * @param ephemeralDurationSeconds 限时消息秒数,0 表示明确关闭;可空表示未观察
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
            Long groupCreatedAt,
            Boolean adminOnlyEditInfo,
            Boolean memberAddMode,
            String description,
            boolean descriptionObserved,
            Boolean joinApprovalMode,
            Integer ephemeralDurationSeconds
    ) {

        /** 兼容尚未上报群创建时间与群设置的调用方。 */
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
                    admin, announceOnly, avatarUrl, null, null, null,
                    null, false, null, null);
        }
    }
}
