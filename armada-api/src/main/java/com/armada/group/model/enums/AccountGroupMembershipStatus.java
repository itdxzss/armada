package com.armada.group.model.enums;

import java.util.Arrays;

/** 账号与群聊之间的当前关系状态。 */
public enum AccountGroupMembershipStatus {
    IN_GROUP(1, true, "在群"),
    UNCONFIRMED(2, true, "未确认"),
    KICKED_OUT(3, false, "被踢出"),
    LEFT(4, false, "已主动退出"),
    NOT_IN_GROUP(5, false, "已不在群");

    private final int code;
    private final boolean sendable;
    private final String text;

    AccountGroupMembershipStatus(int code, boolean sendable, String text) {
        this.code = code;
        this.sendable = sendable;
        this.text = text;
    }

    public int code() {
        return code;
    }

    public boolean sendable() {
        return sendable;
    }

    public String apiValue() {
        return name();
    }

    public String text() {
        return text;
    }

    /**
     * 将数据库码转换为当前状态。读兼容路径把空值和未知历史值视为未确认，写路径不得使用该回退持久化未知码。
     */
    public static AccountGroupMembershipStatus fromCode(Integer code) {
        if (code == null) {
            return UNCONFIRMED;
        }
        return Arrays.stream(values())
                .filter(status -> status.code == code)
                .findFirst()
                .orElse(UNCONFIRMED);
    }
}
