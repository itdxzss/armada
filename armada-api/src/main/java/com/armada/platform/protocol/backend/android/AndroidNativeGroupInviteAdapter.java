package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.platform.protocol.routing.GroupInviteBackend;
import java.net.URI;

/** Android Zhuan 群邀请链接查询后端。 */
public final class AndroidNativeGroupInviteAdapter implements GroupInviteBackend {

    private static final String INVITE_HOST = "chat.whatsapp.com";

    private final AndroidNativeClient client;
    private final AndroidResponseDecoder decoder;
    private final AndroidGroupOperationErrorMapper errorMapper;

    public AndroidNativeGroupInviteAdapter(AndroidNativeClient client,
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
    public GroupInviteResult getInvite(ProtocolAccountRef account, String groupJid) {
        if (account == null || groupJid == null || groupJid.isBlank()) {
            throw new ProtocolException(ProtocolErrorCode.BAD_REQUEST, "群邀请查询参数不能为空");
        }
        AndroidDecodedResponse response = decoder.decode(
                client.groupInvite(account.wsPhone(), groupJid.trim()));
        if (!response.success()) {
            throw errorMapper.toException(response, account, "group.invite", null);
        }
        String inviteUrl = response.data() != null && response.data().isTextual()
                ? response.data().asText().trim() : null;
        String inviteCode = inviteCode(inviteUrl);
        return new GroupInviteResult(groupJid.trim(), inviteCode, inviteUrl);
    }

    private static String inviteCode(String inviteUrl) {
        try {
            URI uri = URI.create(inviteUrl == null ? "" : inviteUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !INVITE_HOST.equalsIgnoreCase(uri.getHost())) {
                throw new IllegalArgumentException();
            }
            String path = uri.getPath();
            String code = path == null ? "" : path.replaceAll("^/+|/+$", "");
            if (code.isBlank() || code.contains("/")) {
                throw new IllegalArgumentException();
            }
            return code;
        } catch (RuntimeException ex) {
            throw new ProtocolException(
                    ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED,
                    "Android 群邀请链接响应无效",
                    ex).withContext(ProtocolBackend.ANDROID, "group.invite", null);
        }
    }
}
