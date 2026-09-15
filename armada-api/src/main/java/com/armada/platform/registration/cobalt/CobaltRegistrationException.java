package com.armada.platform.registration.cobalt;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/** 脱敏注册服务异常；写请求失败后使用原会话 ID 核对结果。 */
public final class CobaltRegistrationException extends BusinessException {
    /** 固定错误分类。 */ private final CobaltRegistrationFailure reason;
    /** HTTP 状态；传输或解析错误为零。 */ private final int httpStatus;
    /** 是否可能已执行写操作。 */ private final boolean outcomeUnknown;

    /** @param reason 固定分类 @param httpStatus HTTP 状态或零 @param outcomeUnknown 写结果是否待核对 */
    public CobaltRegistrationException(CobaltRegistrationFailure reason, int httpStatus, boolean outcomeUnknown) {
        super(ErrorCode.ACCOUNT_REGISTRATION_UNAVAILABLE, "注册服务调用未完成：" + reason.name());
        this.reason = reason;
        this.httpStatus = httpStatus;
        this.outcomeUnknown = outcomeUnknown;
    }
    /** @return 固定故障分类 */ public CobaltRegistrationFailure getReason() { return reason; }
    /** @return HTTP 状态或零 */ public int getHttpStatus() { return httpStatus; }
    /** @return 是否需要查询原会话以确认结果 */ public boolean isOutcomeUnknown() { return outcomeUnknown; }
}
