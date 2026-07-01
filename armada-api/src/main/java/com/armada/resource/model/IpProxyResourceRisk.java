package com.armada.resource.model;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.math.BigDecimal;

/**
 * IP 国家/地区资源风险。
 */
public enum IpProxyResourceRisk {

    /** 系统支持国家下没有任何 IP。 */
    NO_IP("no_ip", "无 IP"),
    /** 资源正常。 */
    NORMAL("normal", "正常"),
    /** 有占用但无空闲 IP。 */
    NO_IDLE("no_idle", "无空闲 IP"),
    /** 可用率低。 */
    LOW_AVAILABLE("low_available", "可用不足"),
    /** 不可用率高。 */
    HIGH_UNAVAILABLE("high_unavailable", "不可用偏高");

    private final String value;
    private final String label;

    IpProxyResourceRisk(String value, String label) {
        this.value = value;
        this.label = label;
    }

    public String value() {
        return value;
    }

    public String label() {
        return label;
    }

    /**
     * 按展示优先级计算风险。NO_IP 优先级最高,NO_IDLE 优先于比例类风险,避免同一行展示多个风险。
     */
    public static IpProxyResourceRisk calculate(
            long total,
            long idle,
            long inUse,
        BigDecimal availableRate,
        BigDecimal unavailableRate) {
        if (total == 0) {
            return NO_IP;
        }
        if (total > 0 && idle == 0 && inUse > 0) {
            return NO_IDLE;
        }
        if (availableRate.compareTo(new BigDecimal("20")) < 0) {
            return LOW_AVAILABLE;
        }
        if (unavailableRate.compareTo(new BigDecimal("50")) > 0) {
            return HIGH_UNAVAILABLE;
        }
        return NORMAL;
    }

    /** 校验前端提交的风险筛选值,空值表示不筛选。 */
    public static void validateFilter(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (IpProxyResourceRisk risk : values()) {
            if (risk.value.equals(value)) {
                return;
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "非法的资源风险: " + value);
    }
}
