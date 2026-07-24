package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;

/** 群组设置协议端口。 */
public interface GroupSettingsPort {

    /** 兼容存量 Web 调用；新业务应传完整账号引用。 */
    default void setEphemeralDuration(
            String protocolAccountId, String groupJid, int durationSeconds) {
        setEphemeralDuration(
                ProtocolAccountRef.legacyWeb(protocolAccountId),
                groupJid,
                durationSeconds);
    }

    /** 兼容存量 Web 调用；新业务应传完整账号引用。 */
    default void setEditGroupSettingsAllowed(
            String protocolAccountId, String groupJid, boolean enabled) {
        setEditGroupSettingsAllowed(
                ProtocolAccountRef.legacyWeb(protocolAccountId), groupJid, enabled);
    }

    /** 兼容存量 Web 调用；新业务应传完整账号引用。 */
    default void setSendMessagesAllowed(
            String protocolAccountId, String groupJid, boolean enabled) {
        setSendMessagesAllowed(
                ProtocolAccountRef.legacyWeb(protocolAccountId), groupJid, enabled);
    }

    /** 兼容存量 Web 调用；新业务应传完整账号引用。 */
    default void setAddMembersAllowed(
            String protocolAccountId, String groupJid, boolean enabled) {
        setAddMembersAllowed(
                ProtocolAccountRef.legacyWeb(protocolAccountId), groupJid, enabled);
    }

    /** 兼容存量 Web 调用；新业务应传完整账号引用。 */
    default void setInviteViaLinkAllowed(
            String protocolAccountId, String groupJid, boolean enabled) {
        setInviteViaLinkAllowed(
                ProtocolAccountRef.legacyWeb(protocolAccountId), groupJid, enabled);
    }

    /** 兼容存量 Web 调用；新业务应传完整账号引用。 */
    default void setJoinApprovalEnabled(
            String protocolAccountId, String groupJid, boolean enabled) {
        setJoinApprovalEnabled(
                ProtocolAccountRef.legacyWeb(protocolAccountId), groupJid, enabled);
    }

    /**
     * 设置群消息自动消失时长。
     *
     * @param protocolAccountId 协议层账号 ID
     * @param groupJid          WhatsApp 群 JID
     * @param durationSeconds   自动消失秒数,0 表示关闭
     */
    void setEphemeralDuration(
            ProtocolAccountRef account,
            String groupJid,
            int durationSeconds);

    /** 设置普通成员是否可以编辑群资料。 */
    void setEditGroupSettingsAllowed(
            ProtocolAccountRef account, String groupJid, boolean enabled);

    /** 设置普通成员是否可以发送消息。 */
    void setSendMessagesAllowed(
            ProtocolAccountRef account, String groupJid, boolean enabled);

    /** 设置普通成员是否可以添加成员。 */
    void setAddMembersAllowed(
            ProtocolAccountRef account, String groupJid, boolean enabled);

    /** 设置是否允许通过邀请链接入群。 */
    void setInviteViaLinkAllowed(
            ProtocolAccountRef account, String groupJid, boolean enabled);

    /** 设置是否启用管理员入群审批。 */
    void setJoinApprovalEnabled(
            ProtocolAccountRef account, String groupJid, boolean enabled);
}
