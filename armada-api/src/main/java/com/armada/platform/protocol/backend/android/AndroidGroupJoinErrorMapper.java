package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;

import java.util.Locale;

/**
 * 把 Android 原生业务失败翻译成 Armada 协议错误语义。
 */
public final class AndroidGroupJoinErrorMapper {

    private static final int APPLICATION_ERROR_HTTP_STATUS = 200;
    private static final String GROUP_JOIN_OPERATION = "group.join";
    private static final String RAW_CODE_BAD_REQUEST = "400";
    private static final String RAW_CODE_UNAUTHORIZED = "401";
    private static final String RAW_CODE_FORBIDDEN = "403";
    private static final String RAW_CODE_RATE_LIMITED = "429";
    private static final String MESSAGE_ACCOUNT_MISSING_OR_OFFLINE = "不存在或已下线";
    private static final String MESSAGE_ACCOUNT_NOT_ONLINE = "不在线";
    private static final String MESSAGE_ACCOUNT_OFFLINE = "离线";
    private static final String MESSAGE_INVITE_CODE_EMPTY = "邀请码为空";
    private static final String MESSAGE_BAD_REQUEST = "bad-request";
    private static final String MESSAGE_GROUP = "group";
    private static final String MESSAGE_GROUP_BANNED = "banned";
    private static final String MESSAGE_GROUP_FULL = "full";
    private static final String MESSAGE_GROUP_GONE = "gone";
    private static final String MESSAGE_GROUP_NOT_FOUND = "not found";
    private static final String MESSAGE_GROUP_UNAVAILABLE = "unavailable";
    private static final String MESSAGE_RATE_OVERLIMIT = "rate-overlimit";
    private static final String MESSAGE_TIME_OUT = "time out";
    private static final String MESSAGE_TIMEOUT = "timeout";

    /**
     * 判断 Android 失败消息是否明确表达账号离线。
     *
     * @param response 已解码的 Android 响应
     * @return 明确表示账号不存在或离线时返回 true
     */
    public boolean isOffline(AndroidDecodedResponse response) {
        String message = lower(response.message());
        return message.contains(MESSAGE_ACCOUNT_MISSING_OR_OFFLINE)
                || message.contains(MESSAGE_ACCOUNT_NOT_ONLINE)
                || message.contains(MESSAGE_ACCOUNT_OFFLINE);
    }

    /**
     * 把 Android 原生失败映射为带统一调用上下文的协议异常。
     *
     * <p>Android 应用层失败通常仍返回 HTTP 200，因此 HTTP 状态保留为 200，原生 IQ code
     * 单独写入 protocolCode；原始消息只参与分类，不直接进入异常消息。</p>
     *
     * @param response 已解码的 Android 响应
     * @param account 当前协议账号引用
     * @param operation 统一操作名称
     * @param operationId 业务操作标识
     * @return 归一化后的协议异常
     */
    public ProtocolException toException(
            AndroidDecodedResponse response,
            ProtocolAccountRef account,
            String operation,
            String operationId) {
        ProtocolErrorCode code = errorCode(response, operation);
        ProtocolException.Metadata metadata = ProtocolException.Metadata.of(
                APPLICATION_ERROR_HTTP_STATUS,
                response.rawProtocolCode(),
                null,
                null);
        return new ProtocolException(
                code,
                metadata,
                safeMessage(code, response.message(), account),
                null)
                .withContext(ProtocolBackend.ANDROID, operation, operationId);
    }

    private ProtocolErrorCode errorCode(AndroidDecodedResponse response, String operation) {
        String message = lower(response.message());
        if (response.validationError() != null) {
            return ProtocolErrorCode.BAD_REQUEST;
        }
        if (isOffline(response)) {
            return ProtocolErrorCode.ACCOUNT_NOT_ONLINE;
        }
        if (message.contains(MESSAGE_INVITE_CODE_EMPTY)) {
            return ProtocolErrorCode.INVALID_GROUP_LINK;
        }
        if (GROUP_JOIN_OPERATION.equals(operation) && isGroupUnavailable(message)) {
            return ProtocolErrorCode.GROUP_UNAVAILABLE;
        }
        if (RAW_CODE_BAD_REQUEST.equals(response.rawProtocolCode())
                && message.contains(MESSAGE_BAD_REQUEST)) {
            // Android 邀请进群只在邀请码校验阶段返回该组合；统一为可触发一次
            // 当前邀请码查询的语义，避免把失效链接误报成群不可用。
            return GROUP_JOIN_OPERATION.equals(operation)
                    ? ProtocolErrorCode.INVITE_INVALID
                    : ProtocolErrorCode.GROUP_UNAVAILABLE;
        }
        if (RAW_CODE_RATE_LIMITED.equals(response.rawProtocolCode())
                || message.contains(MESSAGE_RATE_OVERLIMIT)) {
            return ProtocolErrorCode.ACCOUNT_BUSY;
        }
        if (message.contains(MESSAGE_TIME_OUT) || message.contains(MESSAGE_TIMEOUT)) {
            return ProtocolErrorCode.TIMEOUT;
        }
        if (RAW_CODE_UNAUTHORIZED.equals(response.rawProtocolCode())
                || RAW_CODE_FORBIDDEN.equals(response.rawProtocolCode())) {
            return ProtocolErrorCode.GROUP_JOIN_REJECTED;
        }
        return ProtocolErrorCode.UNKNOWN;
    }

    private static String safeMessage(
            ProtocolErrorCode code,
            String message,
            ProtocolAccountRef account) {
        int length = message == null ? 0 : message.length();
        return "Android 协议调用失败 code=" + code
                + " armadaAccountId=" + account.armadaAccountId()
                + " messageLength=" + length;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static boolean isGroupUnavailable(String message) {
        return message.contains(MESSAGE_GROUP)
                && (message.contains(MESSAGE_GROUP_BANNED)
                || message.contains(MESSAGE_GROUP_FULL)
                || message.contains(MESSAGE_GROUP_GONE)
                || message.contains(MESSAGE_GROUP_NOT_FOUND)
                || message.contains(MESSAGE_GROUP_UNAVAILABLE));
    }
}
