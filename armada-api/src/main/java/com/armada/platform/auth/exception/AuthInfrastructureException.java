package com.armada.platform.auth.exception;

/** Redis 或验证码图片处理不可用时的失败关闭异常。 */
public class AuthInfrastructureException extends RuntimeException {

    public AuthInfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }
}
