package com.armada.resource.model;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/**
 * IP 代理行状态。{@code code} 落库（ip_proxy.status TINYINT），{@link #label()} 为前端展示中文。
 */
public enum IpProxyStatus {

    /** 空闲：可被分配。 */
    IDLE(1, "空闲"),
    /** 使用中：已绑定账号。 */
    IN_USE(2, "使用中"),
    /** 不可用：连续故障达阈值后标记，待人工核查，不自动回池。 */
    UNAVAILABLE(3, "不可用");

    private final int code;
    private final String label;

    IpProxyStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    /** 前端展示中文。 */
    public String label() {
        return label;
    }

    /** 由 tinyint 码转枚举，非法码抛业务异常（禁魔法值、禁返 null）。 */
    public static IpProxyStatus fromCode(Integer code) {
        if (code != null) {
            for (IpProxyStatus v : values()) {
                if (v.code == code) {
                    return v;
                }
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "非法的代理状态: " + code);
    }

    /** 码 → 中文 label（供出参展示）；非法码原样返回 code 字符串，避免出参炸。 */
    public static String labelOf(Integer code) {
        if (code != null) {
            for (IpProxyStatus v : values()) {
                if (v.code == code) {
                    return v.label;
                }
            }
        }
        return String.valueOf(code);
    }
}
