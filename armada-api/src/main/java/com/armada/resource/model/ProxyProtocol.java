package com.armada.resource.model;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/**
 * 代理协议类型。{@code code} 落库（ip_proxy.protocol TINYINT），{@link #label()} 为前端展示。
 *
 * <p>协议名为技术值，label 维持原样（不译中文）。</p>
 */
public enum ProxyProtocol {

    /** HTTP 代理。 */
    HTTP(1, "HTTP"),
    /** SOCKS5 代理，对外展示为 SOCKETS。 */
    SOCKS5(2, "SOCKETS");

    private final int code;
    private final String label;

    ProxyProtocol(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    /** 前端展示。 */
    public String label() {
        return label;
    }

    /** 由 tinyint 码转枚举，非法码抛业务异常。 */
    public static ProxyProtocol fromCode(Integer code) {
        if (code != null) {
            for (ProxyProtocol v : values()) {
                if (v.code == code) {
                    return v;
                }
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "非法的代理协议: " + code);
    }

    /** 码 → label（供出参展示）；非法码原样返回 code 字符串。 */
    public static String labelOf(Integer code) {
        if (code != null) {
            for (ProxyProtocol v : values()) {
                if (v.code == code) {
                    return v.label;
                }
            }
        }
        return String.valueOf(code);
    }
}
