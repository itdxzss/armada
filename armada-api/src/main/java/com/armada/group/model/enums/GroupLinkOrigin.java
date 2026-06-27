package com.armada.group.model.enums;

/** 群入口首次进入群组池来源。 */
public enum GroupLinkOrigin {
    IMPORT(1, "导入链接"),
    JOIN_TASK(2, "进群任务"),
    PULL_TASK(3, "拉群任务"),
    SELF_BUILT(4, "自建群");

    private final int code;
    private final String label;

    GroupLinkOrigin(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static GroupLinkOrigin fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (GroupLinkOrigin value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知群入口来源: " + code);
    }
}
