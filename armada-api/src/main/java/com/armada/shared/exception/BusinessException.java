package com.armada.shared.exception;

/**
 * 业务异常。面向运营可恢复的错误(校验失败、资源冲突等)统一抛此异常,
 * 由全局异常处理器转成 {@code ApiResponse.code != 0}。
 *
 * <p>铁律:可恢复错误必须抛业务异常,不能落到 HTTP 200 被前端当成功吞掉。</p>
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.code();
    }

    public int getCode() {
        return code;
    }
}
