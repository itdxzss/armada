package com.armada.group.model.enums;

/** 创建者号码证据级别；数值与持久化优先级一致。 */
public enum GroupCreatorPhoneSource {
    /** 未取得号码。 */
    UNKNOWN(0),
    /** 从老格式群 JID 推导，仅用于创建信息投影。 */
    JID_DERIVED(1),
    /** 协议确认或迁移前已保存的号码，维持原保护级别。 */
    CONFIRMED(2);

    private final int code;

    GroupCreatorPhoneSource(int code) {
        this.code = code;
    }

    /** 返回持久化来源代码。 */
    public int code() {
        return code;
    }
}
