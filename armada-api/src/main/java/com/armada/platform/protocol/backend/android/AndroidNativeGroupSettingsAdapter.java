package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.routing.GroupSettingsBackend;

/** Android Zhuan 群设置后端。 */
public final class AndroidNativeGroupSettingsAdapter implements GroupSettingsBackend {

    private final AndroidNativeClient client;
    private final AndroidResponseDecoder decoder;
    private final AndroidGroupOperationErrorMapper errorMapper;

    public AndroidNativeGroupSettingsAdapter(AndroidNativeClient client,
                                             AndroidResponseDecoder decoder,
                                             AndroidGroupOperationErrorMapper errorMapper) {
        this.client = client;
        this.decoder = decoder;
        this.errorMapper = errorMapper;
    }

    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.ANDROID;
    }

    @Override
    public void setSendMessagesAllowed(ProtocolAccountRef account, String groupJid, boolean enabled) {
        requireAccount(account);
        AndroidDecodedResponse response = decoder.decode(
                client.setGroupAnnouncement(account.wsPhone(), requireGroup(groupJid), enabled));
        if (!response.success()) {
            throw errorMapper.toException(response, account, "group.settings.announcement", null);
        }
    }

    @Override
    public void setEphemeralDuration(ProtocolAccountRef account, String groupJid, int durationSeconds) {
        throw unsupported(account, "ephemeral");
    }

    @Override
    public void setEditGroupSettingsAllowed(ProtocolAccountRef account, String groupJid, boolean enabled) {
        throw unsupported(account, "edit");
    }

    @Override
    public void setAddMembersAllowed(ProtocolAccountRef account, String groupJid, boolean enabled) {
        requireAccount(account);
        AndroidDecodedResponse response = decoder.decode(
                client.setGroupMemberAddMode(
                        account.wsPhone(), requireGroup(groupJid), enabled));
        if (!response.success()) {
            throw errorMapper.toException(
                    response, account, "group.settings.member-add", null);
        }
    }

    @Override
    public void setInviteViaLinkAllowed(ProtocolAccountRef account, String groupJid, boolean enabled) {
        throw unsupported(account, "invite-link");
    }

    @Override
    public void setJoinApprovalEnabled(ProtocolAccountRef account, String groupJid, boolean enabled) {
        throw unsupported(account, "join-approval");
    }

    private static ProtocolException unsupported(ProtocolAccountRef account, String capability) {
        ProtocolBackend backend = account == null ? ProtocolBackend.ANDROID : account.backend();
        return new ProtocolException(
                ProtocolErrorCode.GROUP_CAPABILITY_UNSUPPORTED,
                "Android 协议暂不支持群设置: " + capability)
                .withContext(backend, "group.settings." + capability, null);
    }

    private static void requireAccount(ProtocolAccountRef account) {
        if (account == null) {
            throw new ProtocolException(ProtocolErrorCode.BAD_REQUEST, "群设置操作账号不能为空");
        }
    }

    private static String requireGroup(String groupJid) {
        if (groupJid == null || groupJid.isBlank()) {
            throw new ProtocolException(ProtocolErrorCode.BAD_REQUEST, "groupJid 不能为空");
        }
        return groupJid.trim();
    }
}
