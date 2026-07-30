package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;

import java.util.Locale;

/**
 * 把 Android 原生联系人和群操作失败转换为 Armada 协议错误语义。
 */
public final class AndroidGroupOperationErrorMapper {

    private static final int APPLICATION_ERROR_HTTP_STATUS = 200;

    /**
     * 映射 Android 建群失败，并把明确的触达频控归类为账号限制。
     *
     * @param response 已解码的 Android 响应
     * @param account 当前协议账号引用
     * @param operationId 业务操作标识
     * @return 带统一上下文的协议异常
     */
    public ProtocolException toGroupCreateException(
            AndroidDecodedResponse response,
            ProtocolAccountRef account,
            String operationId) {
        return mapped(response, account, "group.create", operationId, true);
    }

    /**
     * 映射一般 Android 联系人或群操作失败。
     *
     * @param response 已解码的 Android 响应
     * @param account 当前协议账号引用
     * @param operation 统一操作名称
     * @param operationId 业务操作标识
     * @return 带统一上下文的协议异常
     */
    public ProtocolException toException(
            AndroidDecodedResponse response,
            ProtocolAccountRef account,
            String operation,
            String operationId) {
        return mapped(response, account, operation, operationId, false);
    }

    private ProtocolException mapped(
            AndroidDecodedResponse response,
            ProtocolAccountRef account,
            String operation,
            String operationId,
            boolean groupCreate) {
        ProtocolErrorCode code = errorCode(response, groupCreate);
        ProtocolException.Metadata metadata = ProtocolException.Metadata.of(
                APPLICATION_ERROR_HTTP_STATUS,
                response.rawProtocolCode(),
                null,
                null);
        return new ProtocolException(
                code,
                metadata,
                "Android 协议调用失败 code=" + code
                        + " armadaAccountId=" + account.armadaAccountId()
                        + " messageLength="
                        + (response.message() == null ? 0 : response.message().length()),
                null)
                .withContext(ProtocolBackend.ANDROID, operation, operationId);
    }

    private static ProtocolErrorCode errorCode(
            AndroidDecodedResponse response,
            boolean groupCreate) {
        String message = response.message() == null
                ? ""
                : response.message().toLowerCase(Locale.ROOT);
        if (response.validationError() != null) {
            return ProtocolErrorCode.BAD_REQUEST;
        }
        if (message.contains("不存在或已下线")
                || message.contains("不在线")
                || message.contains("离线")) {
            return ProtocolErrorCode.ACCOUNT_NOT_ONLINE;
        }
        if (message.contains("time out") || message.contains("timeout")) {
            return ProtocolErrorCode.TIMEOUT;
        }
        boolean unauthorized = "401".equals(response.rawProtocolCode())
                || message.contains("not-authorized")
                || message.contains("not authorized")
                || message.contains("code: 401");
        if (unauthorized) {
            return ProtocolErrorCode.GROUP_PERMISSION_DENIED;
        }
        boolean rateLimited = "429".equals(response.rawProtocolCode())
                || message.contains("rate-overlimit");
        if (groupCreate && rateLimited) {
            return ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED;
        }
        if (rateLimited) {
            return ProtocolErrorCode.ACCOUNT_BUSY;
        }
        return ProtocolErrorCode.UNKNOWN;
    }
}
