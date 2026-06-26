package com.armada.platform.protocol.exception;

import java.util.Optional;

/**
 * 协议层防腐层异常。
 *
 * <p>本异常只表达调用 armada-protocol / laqunxitong 时的下游失败,不直接进入全局
 * {@code BusinessException} 错误码体系。业务编排捕获后再决定重试、退避、落状态或转业务异常。</p>
 */
public class ProtocolException extends RuntimeException {

    private static final String DEFAULT_MESSAGE = "协议层调用失败";
    private static final int NO_HTTP_STATUS = 0;

    private final ProtocolErrorCode errorCode;
    private final int httpStatus;
    private final String protocolCode;
    private final Long retryAfterMs;
    private final String ownerEndpoint;

    /**
     * 创建不带协议层元数据的协议异常。
     *
     * @param errorCode 防腐层错误码;为空时兜底为 {@link ProtocolErrorCode#UNKNOWN}
     * @param message   异常消息;为空白时使用默认消息
     */
    public ProtocolException(ProtocolErrorCode errorCode, String message) {
        this(errorCode, Metadata.empty(), message, null);
    }

    /**
     * 创建不带协议层元数据但保留原始 cause 的协议异常。
     *
     * @param errorCode 防腐层错误码;为空时兜底为 {@link ProtocolErrorCode#UNKNOWN}
     * @param message   异常消息;为空白时使用默认消息
     * @param cause     原始异常,通常来自 HTTP 客户端或 JSON 解析
     */
    public ProtocolException(ProtocolErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, Metadata.empty(), message, cause);
    }

    /**
     * 创建带协议层元数据的协议异常。
     *
     * @param errorCode 防腐层错误码;为空时兜底为 {@link ProtocolErrorCode#UNKNOWN}
     * @param metadata  协议层元数据;为空时按无元数据处理
     * @param message   异常消息;为空白时使用默认消息
     * @param cause     原始异常,通常来自 HTTP 客户端或 JSON 解析
     */
    public ProtocolException(
            ProtocolErrorCode errorCode,
            Metadata metadata,
            String message,
            Throwable cause) {
        super(normalizeMessage(message), cause);
        Metadata safeMetadata = metadata == null ? Metadata.empty() : metadata;
        this.errorCode = errorCode == null ? ProtocolErrorCode.UNKNOWN : errorCode;
        this.httpStatus = safeMetadata.httpStatus;
        this.protocolCode = safeMetadata.protocolCode;
        this.retryAfterMs = safeMetadata.retryAfterMs;
        this.ownerEndpoint = safeMetadata.ownerEndpoint;
    }

    /**
     * 创建未识别协议失败异常。
     *
     * @param message 异常消息;为空白时使用默认消息
     * @param cause   原始异常
     * @return UNKNOWN 类型协议异常
     */
    public static ProtocolException unknown(String message, Throwable cause) {
        return new ProtocolException(ProtocolErrorCode.UNKNOWN, message, cause);
    }

    /**
     * 获取防腐层错误码。
     *
     * @return 非空错误码
     */
    public ProtocolErrorCode errorCode() {
        return errorCode;
    }

    /**
     * 获取协议层 HTTP 状态码。
     *
     * @return HTTP 状态码;无 HTTP 响应时为 0
     */
    public int httpStatus() {
        return httpStatus;
    }

    /**
     * 获取协议层原始错误码。
     *
     * @return 原始错误码;协议层未返回时为空
     */
    public Optional<String> protocolCode() {
        return Optional.ofNullable(protocolCode);
    }

    /**
     * 获取协议层建议的重试等待时间。
     *
     * @return retryAfterMs;协议层未返回时为空
     */
    public Optional<Long> retryAfterMs() {
        return Optional.ofNullable(retryAfterMs);
    }

    /**
     * 获取 owner worker endpoint。
     *
     * @return ownerEndpoint;仅 NOT_OWNER 等场景可能存在
     */
    public Optional<String> ownerEndpoint() {
        return Optional.ofNullable(ownerEndpoint);
    }

    private static String normalizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return DEFAULT_MESSAGE;
        }
        return message;
    }

    private static String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private static Long normalizeRetryAfterMs(Long value) {
        if (value == null || value < 0) {
            return null;
        }
        return value;
    }

    /**
     * 协议层错误响应中的可选元数据。
     */
    public static final class Metadata {

        private static final Metadata EMPTY = new Metadata(NO_HTTP_STATUS, null, null, null);

        private final int httpStatus;
        private final String protocolCode;
        private final Long retryAfterMs;
        private final String ownerEndpoint;

        private Metadata(int httpStatus, String protocolCode, Long retryAfterMs, String ownerEndpoint) {
            this.httpStatus = Math.max(httpStatus, NO_HTTP_STATUS);
            this.protocolCode = normalizeText(protocolCode);
            this.retryAfterMs = normalizeRetryAfterMs(retryAfterMs);
            this.ownerEndpoint = normalizeText(ownerEndpoint);
        }

        /**
         * 创建空协议元数据。
         *
         * @return 空元数据对象
         */
        public static Metadata empty() {
            return EMPTY;
        }

        /**
         * 创建协议层错误响应元数据。
         *
         * @param httpStatus    HTTP 状态码;无响应时传 0
         * @param protocolCode  协议层原始错误码
         * @param retryAfterMs  协议层建议重试等待时间
         * @param ownerEndpoint NOT_OWNER 场景下的 owner worker endpoint
         * @return 协议元数据对象
         */
        public static Metadata of(
                int httpStatus,
                String protocolCode,
                Long retryAfterMs,
                String ownerEndpoint) {
            return new Metadata(httpStatus, protocolCode, retryAfterMs, ownerEndpoint);
        }
    }
}
