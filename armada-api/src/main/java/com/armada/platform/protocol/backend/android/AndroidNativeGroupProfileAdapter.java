package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupPictureResult;
import com.armada.platform.protocol.routing.GroupProfileBackend;

/** Android Zhuan 群资料后端。 */
public final class AndroidNativeGroupProfileAdapter implements GroupProfileBackend {

    private final AndroidNativeClient client;
    private final AndroidResponseDecoder decoder;
    private final AndroidGroupOperationErrorMapper errorMapper;

    public AndroidNativeGroupProfileAdapter(
            AndroidNativeClient client,
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
    public void updateSubject(ProtocolAccountRef account, String groupJid, String subject) {
        requireAccount(account);
        AndroidDecodedResponse response = decoder.decode(client.setGroupName(
                account.wsPhone(), requireText(groupJid, "groupJid"), requireText(subject, "subject")));
        if (!response.success()) {
            throw errorMapper.toException(response, account, "group.profile.subject", null);
        }
    }

    @Override
    public GroupPictureResult updatePicture(
            ProtocolAccountRef account, String groupJid, String url, String base64) {
        requireAccount(account);
        if (url != null && !url.isBlank()) {
            throw unsupported(account, "picture-url");
        }
        AndroidDecodedResponse response = decoder.decode(client.setGroupPicture(
                account.wsPhone(), requireText(groupJid, "groupJid"), requireText(base64, "base64")));
        if (!response.success()) {
            throw errorMapper.toException(response, account, "group.profile.picture", null);
        }
        return new GroupPictureResult(true, null);
    }

    @Override
    public void updateDescription(ProtocolAccountRef account, String groupJid, String description) {
        throw unsupported(account, "description");
    }

    @Override
    public void updateAnnouncementText(ProtocolAccountRef account, String groupJid, String text) {
        throw unsupported(account, "announcement-text");
    }

    @Override
    public String getPictureUrl(ProtocolAccountRef account, String groupJid) {
        throw unsupported(account, "picture-query");
    }

    private static ProtocolException unsupported(ProtocolAccountRef account, String capability) {
        ProtocolBackend backend = account == null ? ProtocolBackend.ANDROID : account.backend();
        return new ProtocolException(
                ProtocolErrorCode.GROUP_CAPABILITY_UNSUPPORTED,
                "Android 协议暂不支持群资料能力: " + capability)
                .withContext(backend, "group.profile." + capability, null);
    }

    private static void requireAccount(ProtocolAccountRef account) {
        if (account == null) {
            throw new ProtocolException(ProtocolErrorCode.BAD_REQUEST, "群资料操作账号不能为空");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ProtocolException(ProtocolErrorCode.BAD_REQUEST, field + " 不能为空");
        }
        return value.trim();
    }
}
