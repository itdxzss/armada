package com.armada.marketing.grouppull.model.enums;

/** 拉群营销建群后的群组发言权限操作。 */
public enum GroupPullSpeakPermission {

    /** 不调用发言权限修改接口。 */
    UNCHANGED(1),

    /** 仅管理员可发言。 */
    MUTED(2),

    /** 全体成员可发言。 */
    UNMUTED(3);

    private final int code;

    GroupPullSpeakPermission(int code) {
        this.code = code;
    }

    /** 返回数据库持久化码值。 */
    public int code() {
        return code;
    }

    /** 按数据库码值解析发言权限。 */
    public static GroupPullSpeakPermission fromCode(int code) {
        return switch (code) {
            case 1 -> UNCHANGED;
            case 2 -> MUTED;
            case 3 -> UNMUTED;
            default -> throw new IllegalArgumentException("未知拉群发言权限: " + code);
        };
    }
}
