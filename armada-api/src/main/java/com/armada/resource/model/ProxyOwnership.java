package com.armada.resource.model;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/**
 * IP 代理归属。{@code code} 落库（ip_proxy.ownership TINYINT），{@link #label()} 为前端展示中文。
 *
 * <p>三级代理模型：租户自有 / 平台共享池 / 租借（admin 临时分发给租户）。</p>
 */
public enum ProxyOwnership {

    /** 租户自有：租户自行导入，或 admin 永久分配给租户。 */
    OWNED(1, "租户自有"),
    /** 平台池：admin 导入、未分配（tenant_id 为空）。 */
    PLATFORM_POOL(2, "平台池"),
    /** 租借：admin 临时分发给租户。 */
    LEASED(3, "租借");

    private final int code;
    private final String label;

    ProxyOwnership(int code, String label) {
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

    /** 由 tinyint 码转枚举，非法码抛业务异常。 */
    public static ProxyOwnership fromCode(Integer code) {
        if (code != null) {
            for (ProxyOwnership v : values()) {
                if (v.code == code) {
                    return v;
                }
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "非法的代理归属: " + code);
    }

    /** 码 → 中文 label（供出参展示）；非法码原样返回 code 字符串。 */
    public static String labelOf(Integer code) {
        if (code != null) {
            for (ProxyOwnership v : values()) {
                if (v.code == code) {
                    return v.label;
                }
            }
        }
        return String.valueOf(code);
    }
}
