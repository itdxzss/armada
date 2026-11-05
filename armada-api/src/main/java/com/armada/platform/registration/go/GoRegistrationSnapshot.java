package com.armada.platform.registration.go;

/**
 * Go 注册事务的安全视图；不含号码、验证码、身份或原始回执。
 * @param transactionId Go 事务 ID，响应没有时为空串
 * @param state 事务状态
 * @param version 事务当前版本，下一次验码必须使用发码成功时返回的值
 * @param resultAvailable 注册结果是否已保存可用；REGISTERED 但为 false 不算成功
 * @param errorCode Go 的 error_code，没有时为空串
 */
public record GoRegistrationSnapshot(String transactionId, GoRegistrationState state, long version,
        boolean resultAvailable, String errorCode) {

    /** @return 没有响应内容时使用的空视图，避免调用方处理 null */
    public static GoRegistrationSnapshot empty() {
        return new GoRegistrationSnapshot("", GoRegistrationState.UNRECOGNIZED, 0L, false, "");
    }
}
