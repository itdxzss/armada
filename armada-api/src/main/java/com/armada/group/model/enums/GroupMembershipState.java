package com.armada.group.model.enums;

/** 我方与群当前关系态。 */
public enum GroupMembershipState {
    TARGET(1, "目标未进群"),
    JOINED(2, "已进群"),
    OWNER(3, "自建拥有");

    private final int code;
    private final String label;

    GroupMembershipState(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static GroupMembershipState fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (GroupMembershipState value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知群关系态: " + code);
    }
}
