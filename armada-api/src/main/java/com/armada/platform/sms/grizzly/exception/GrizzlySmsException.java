package com.armada.platform.sms.grizzly.exception;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/** 脱敏的接码业务异常，单独标识不能盲目重试的写操作。 */
public final class GrizzlySmsException extends BusinessException {

    /** 固定错误分类，不含 API 密钥、手机号或短信。 */
    private final GrizzlySmsFailure reason;
    /** 写请求可能已生效，调用方须核对后才能再次发起。 */
    private final boolean outcomeUnknown;

    /**
     * 创建供应商异常，不保留可能包含请求 URL 或响应正文的原始异常链。
     * @param reason 固定错误分类
     * @param outcomeUnknown 是否无法确定写请求的最终结果
     */
    public GrizzlySmsException(GrizzlySmsFailure reason, boolean outcomeUnknown) {
        super(ErrorCode.SMS_PROVIDER_UNAVAILABLE, "接码平台调用未完成：" + reason.name()
                + (outcomeUnknown ? "，操作结果待核对，请勿直接重试" : ""));
        this.reason = reason;
        this.outcomeUnknown = outcomeUnknown;
    }

    /** @return 不包含供应商原始文本的错误分类 */
    public GrizzlySmsFailure getReason() {
        return reason;
    }

    /** @return true 时禁止把失败直接当作未购买或未变更 */
    public boolean isOutcomeUnknown() {
        return outcomeUnknown;
    }
}
