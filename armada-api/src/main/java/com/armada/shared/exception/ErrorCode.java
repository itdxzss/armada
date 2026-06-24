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
    CONFLICT(40901, "资源冲突"),

    /** 缺少租户标识(请求未带 X-Tenant-Code)。 */
    TENANT_MISSING(40101, "缺少租户标识,请重新登录"),

    /** 租户码无效或租户已停用。 */
    TENANT_NOT_FOUND(40102, "租户不存在或已停用");

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
