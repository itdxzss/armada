package com.armada.account.model.enums;

/** 以创建时的左右分组确定文案，后续移组不改变发送内容。 */
public enum AccountMutualChatDirection {
    /** A 向 B 发 A 文案。 */
    LEFT_TO_RIGHT(1),
    /** B 向 A 发 B 文案。 */
    RIGHT_TO_LEFT(2);
    private final int code;
    AccountMutualChatDirection(int code) { this.code = code; }
    /** 数据库中的方向值。 */
    public int code() { return code; }
}
