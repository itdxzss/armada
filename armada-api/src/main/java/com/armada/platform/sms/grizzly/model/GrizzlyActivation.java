package com.armada.platform.sms.grizzly.model;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 已购买号码的信息，不代表目标账号已注册。
 * @param activationId 接码订单 ID
 * @param phoneNumber 供应商返回的完整国际号码
 * @param cost 供应商报告的本次成本
 * @param currency 供应商报告的 ISO 4217 数字币种，不能默认美元
 * @param details 国家、时间及后续收码能力
 */
public record GrizzlyActivation(String activationId, String phoneNumber, BigDecimal cost,
                                int currency, Details details) {

    /** 避免日志和异常上下文通过 record 的默认字符串输出完整号码。 */
    @Override
    public String toString() {
        return "GrizzlyActivation[activationId=" + activationId + ", phoneNumber=[REDACTED]]";
    }

    /**
     * 供应商可选元数据；未标注时区的时间保留原文，不能直接当作 UTC。
     * @param countryCode 供应商国家 ID
     * @param activationTime 供应商返回的购买时间
     * @param activationEnd 供应商返回的结束时间
     * @param activationCancel 供应商取消时间字段，不推断最早或最晚取消语义
     * @param canGetAnotherSms 再次收码能力原始标记；文档解释存在冲突，不自动转换为布尔或触发重发
     */
    public record Details(Optional<String> countryCode, Optional<String> activationTime,
                          Optional<String> activationEnd, Optional<String> activationCancel,
                          Optional<String> canGetAnotherSms) {
        /** 避免供应商元数据通过默认字符串输出进入日志。 */
        @Override
        public String toString() {
            return "GrizzlyActivation.Details[REDACTED]";
        }
    }
}
