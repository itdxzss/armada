package com.armada.marketing.model;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/**
 * 营销模板超链模式。
 */
public enum LinkMode {

    /** 普通超链:推广链接作普通内容展示,无消息按钮。 */
    NORMAL(1),

    /** 按钮超链:通过消息按钮承载,最多 3 个按钮。 */
    BUTTON(2);

    private final int code;

    LinkMode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** 由 tinyint 码转枚举,非法码抛业务异常(禁魔法值、禁返 null)。 */
    public static LinkMode fromCode(Integer code) {
        if (code != null) {
            for (LinkMode mode : values()) {
                if (mode.code == code) {
                    return mode;
                }
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "非法的超链模式: " + code);
    }
}
