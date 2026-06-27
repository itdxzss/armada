package com.armada.group.model.enums;

/** 群链接健康检测状态。 */
public enum GroupLinkHealthStatus {
    AVAILABLE(1, "可用"),
    LINK_INVALID(2, "链接失效"),
    UNAVAILABLE(3, "不可用");

    private final int code;
    private final String label;

    GroupLinkHealthStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static GroupLinkHealthStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (GroupLinkHealthStatus value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知群健康状态: " + code);
    }
}
