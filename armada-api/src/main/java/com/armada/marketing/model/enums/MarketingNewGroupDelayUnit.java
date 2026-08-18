package com.armada.marketing.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/** 账号动态目标新群首次发送延迟单位。 */
public enum MarketingNewGroupDelayUnit {

    /** 按分钟配置，允许 1 到 60。 */
    MINUTE(1, 1, 60, 60_000L),

    /** 按小时配置，允许 1 到 24。 */
    HOUR(2, 1, 24, 3_600_000L);

    private final int code;
    private final int minValue;
    private final int maxValue;
    private final long milliseconds;

    MarketingNewGroupDelayUnit(int code, int minValue, int maxValue, long milliseconds) {
        this.code = code;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.milliseconds = milliseconds;
    }

    /** 数据库存储码。 */
    public int code() {
        return code;
    }

    /** API 使用的稳定枚举值。 */
    public String apiValue() {
        return name();
    }

    /** 判断数值是否落在当前单位允许范围。 */
    public boolean supports(int value) {
        return value >= minValue && value <= maxValue;
    }

    /** 把合法配置转换为毫秒。 */
    public long toMilliseconds(int value) {
        return Math.multiplyExact(value, milliseconds);
    }

    /** 从 API 字符串解析单位；空值按分钟处理以兼容旧客户端。 */
    public static MarketingNewGroupDelayUnit fromApiValue(String value) {
        if (value == null || value.isBlank()) {
            return MINUTE;
        }
        for (MarketingNewGroupDelayUnit unit : values()) {
            if (unit.name().equalsIgnoreCase(value.trim())) {
                return unit;
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "延迟单位只支持分钟或小时");
    }

    /** 从数据库码解析单位；历史空值按分钟处理。 */
    public static MarketingNewGroupDelayUnit fromCode(Integer code) {
        if (code == null) {
            return MINUTE;
        }
        for (MarketingNewGroupDelayUnit unit : values()) {
            if (unit.code == code) {
                return unit;
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "延迟单位配置无效");
    }
}
