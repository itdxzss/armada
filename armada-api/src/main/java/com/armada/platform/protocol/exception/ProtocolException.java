package com.armada.platform.protocol.exception;

import com.armada.platform.protocol.model.enums.ProtocolBackend;

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
    private final Boolean retryable;
    private final ProtocolBackend backend;
    private final String operation;
    private final String operationId;

    /**
     * 创建不带协议层元数据的协议异常。
     */
    public ProtocolException(ProtocolErrorCode errorCode, String message) {
        this(errorCode, Metadata.empty(), message, null);
    }

    /**
     * 创建不带协议层元数据但保留原始 cause 的协议异常。
     */
    public ProtocolException(ProtocolErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, Metadata.empty(), message, cause);
    }

    /**
     * 创建带协议层元数据的协议异常。
     */
    public ProtocolException(
            ProtocolErrorCode errorCode,
            Metadata metadata,
            String message,
            Throwable cause) {
        this(errorCode, metadata, message, cause, null, null, null);
    }

    /**
     * 创建带协议元数据和 Armada 调用上下文的异常。
     */
    private ProtocolException(
            ProtocolErrorCode errorCode,
            Metadata metadata,
            String message,
            Throwable cause,
            ProtocolBackend backend,
            String operation,
            String operationId) {
        super(normalizeMessage(message), cause);
        Metadata safeMetadata = metadata == null ? Metadata.empty() : metadata;
        this.errorCode = errorCode == null ? ProtocolErrorCode.UNKNOWN : errorCode;
        this.httpStatus = safeMetadata.httpStatus;
        this.protocolCode = safeMetadata.protocolCode;
        this.retryAfterMs = safeMetadata.retryAfterMs;
        this.ownerEndpoint = safeMetadata.ownerEndpoint;
        this.retryable = safeMetadata.retryable;
        this.backend = backend;
        this.operation = operation;
        this.operationId = operationId;
    }

    /**
     * 创建未识别协议失败异常。
     */
    public static ProtocolException unknown(String message, Throwable cause) {
        return new ProtocolException(ProtocolErrorCode.UNKNOWN, message, cause);
    }

    public ProtocolErrorCode errorCode() {
        return errorCode;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public Optional<String> protocolCode() {
        return Optional.ofNullable(protocolCode);
    }

    public Optional<Long> retryAfterMs() {
        return Optional.ofNullable(retryAfterMs);
    }

    public Optional<String> ownerEndpoint() {
        return Optional.ofNullable(ownerEndpoint);
    }

    /**
     * 获取协议层对本次失败是否可重试的明确判断。
     *
     * @return 协议层未提供时为空
     */
    public Optional<Boolean> retryable() {
        return Optional.ofNullable(retryable);
    }

    public Optional<ProtocolBackend> backend() {
        return Optional.ofNullable(backend);
    }

    public Optional<String> operation() {
        return Optional.ofNullable(operation);
    }

    public Optional<String> operationId() {
        return Optional.ofNullable(operationId);
    }

    /**
     * 在保留错误码、协议元数据、重试标记、消息和 cause 的同时附加统一调用上下文。
     */
    public ProtocolException withContext(
            ProtocolBackend backend,
            String operation,
            String operationId) {
        return new ProtocolException(
                errorCode,
                Metadata.of(httpStatus, protocolCode, retryAfterMs, ownerEndpoint, retryable),
                getMessage(),
                getCause(),
                backend,
                normalizeText(operation),
                normalizeText(operationId));
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

        private static final Metadata EMPTY = new Metadata(NO_HTTP_STATUS, null, null, null, null);

        private final int httpStatus;
        private final String protocolCode;
        private final Long retryAfterMs;
        private final String ownerEndpoint;
        private final Boolean retryable;

        private Metadata(
                int httpStatus,
                String protocolCode,
                Long retryAfterMs,
                String ownerEndpoint,
                Boolean retryable) {
            this.httpStatus = Math.max(httpStatus, NO_HTTP_STATUS);
            this.protocolCode = normalizeText(protocolCode);
            this.retryAfterMs = normalizeRetryAfterMs(retryAfterMs);
            this.ownerEndpoint = normalizeText(ownerEndpoint);
            this.retryable = retryable;
        }

        public static Metadata empty() {
            return EMPTY;
        }

        public static Metadata of(
                int httpStatus,
                String protocolCode,
                Long retryAfterMs,
                String ownerEndpoint) {
            return new Metadata(httpStatus, protocolCode, retryAfterMs, ownerEndpoint, null);
        }

        /**
         * 创建包含协议层重试判断的错误元数据。
         */
        public static Metadata of(
                int httpStatus,
                String protocolCode,
                Long retryAfterMs,
                String ownerEndpoint,
                Boolean retryable) {
            return new Metadata(httpStatus, protocolCode, retryAfterMs, ownerEndpoint, retryable);
        }
    }
}
