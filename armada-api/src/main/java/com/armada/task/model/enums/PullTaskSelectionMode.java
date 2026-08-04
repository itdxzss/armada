package com.armada.task.model.enums;

/** 角色账号选择方式。 */
public enum PullTaskSelectionMode {

    /** 调度器按分组规则自动选择。 */
    AUTOMATIC(1),
    /** 用户在补充页明确选择。 */
    MANUAL(2);

    private final int code;

    PullTaskSelectionMode(int code) {
        this.code = code;
    }

    /** @return 数据库存储值 */
    public int code() {
        return code;
    }

    /** @return 匹配的选择方式；未知值返回 null */
    public static PullTaskSelectionMode fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (PullTaskSelectionMode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return null;
    }
}
