package com.armada.resource.model;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.springframework.util.StringUtils;

/**
 * IP 代理分配方式。{@code value} 落库到 {@code ip_proxy.allocation_mode},label 为前端展示中文。
 */
public enum IpProxyAllocationMode {

    /** 智能分配:按账号导入国家优先匹配该国家 IP。 */
    SMART("smart", "智能分配"),
    /** 混合分组:作为跨国家可用的混合 IP 池参与兜底分配。 */
    MIXED("mixed", "混合分组");

    private final String value;
    private final String label;

    IpProxyAllocationMode(String value, String label) {
        this.value = value;
        this.label = label;
    }

    public String value() {
        return value;
    }

    /** 前端展示中文。 */
    public String label() {
        return label;
    }

    /**
     * 归一化导入/查询提交值。
     *
     * <p>兼容原型中出现过的 {@code mixed_country};空值按默认智能分配处理。</p>
     *
     * @param value 前端提交值
     * @return 规范化枚举
     * @throws BusinessException 非法分配方式时抛出
     */
    public static IpProxyAllocationMode fromValue(String value) {
        if (!StringUtils.hasText(value)) {
            return SMART;
        }
        String normalized = value.trim();
        if ("mixed_country".equals(normalized)) {
            return MIXED;
        }
        for (IpProxyAllocationMode mode : values()) {
            if (mode.value.equals(normalized)) {
                return mode;
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "非法的 IP 分配方式: " + value);
    }

    /** 码值 → 中文 label;非法值原样返回,避免历史脏数据导致统计页失败。 */
    public static String labelOf(String value) {
        if (StringUtils.hasText(value)) {
            String normalized = "mixed_country".equals(value.trim()) ? MIXED.value : value.trim();
            for (IpProxyAllocationMode mode : values()) {
                if (mode.value.equals(normalized)) {
                    return mode.label;
                }
            }
        }
        return String.valueOf(value);
    }
}
