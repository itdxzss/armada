package com.armada.shared.exception;

/**
 * 业务错误码。配合 {@link BusinessException} 使用,禁止魔法值。
 */
public enum ErrorCode {

    /** 参数/业务校验失败。 */
    VALIDATION(40001, "参数校验失败"),

    /** 资源不存在。 */
    NOT_FOUND(40401, "资源不存在"),

    /** 资源冲突(如名称重复)。 */
    CONFLICT(40901, "资源冲突");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
