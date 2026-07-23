package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.routing.GroupLeaveBackend;
import java.util.Locale;

/** Android Zhuan 退群后端。 */
public final class AndroidNativeGroupLeaveAdapter implements GroupLeaveBackend {

    private final AndroidNativeClient client;
    private final AndroidResponseDecoder decoder;
    private final AndroidGroupOperationErrorMapper errorMapper;

    public AndroidNativeGroupLeaveAdapter(AndroidNativeClient client,
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
    public void leave(ProtocolAccountRef account, String groupJid) {
        if (account == null || groupJid == null || groupJid.isBlank()) {
            throw new ProtocolException(ProtocolErrorCode.BAD_REQUEST, "退群参数不能为空");
        }
        AndroidDecodedResponse response = decoder.decode(
                client.leaveGroup(account.wsPhone(), groupJid.trim()));
        if (!response.success() && !alreadyLeft(response.message())) {
            throw errorMapper.toException(response, account, "group.leave", null);
        }
    }

    private static boolean alreadyLeft(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("ALREADY_LEFT") || normalized.equals("ACCOUNT_NOT_PARTICIPANT");
    }
}
