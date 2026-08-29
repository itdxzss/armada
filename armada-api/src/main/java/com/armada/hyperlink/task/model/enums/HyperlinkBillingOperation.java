package com.armada.hyperlink.task.model.enums;

/** 任务计费 Saga 当前待恢复的外部动作。 */
public enum HyperlinkBillingOperation {
    /** 当前没有待恢复外部动作。 */
    NONE(0),
    /** 首次冻结整份受众报价。 */
    RESERVE(1),
    /** 编辑重建时调整既有外部预约金额。 */
    ADJUST(2),
    /** 按唯一实际发送 recipient 结算。 */
    SETTLE(3),
    /** 释放结算后的剩余冻结金额。 */
    RELEASE(4);

    private final int code;

    HyperlinkBillingOperation(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** 按数据库码解析待恢复动作。 */
    public static HyperlinkBillingOperation fromCode(Integer code) {
        for (HyperlinkBillingOperation operation : values()) {
            if (Integer.valueOf(operation.code).equals(code)) {
                return operation;
            }
        }
        throw new IllegalArgumentException("未知超链计费操作: " + code);
    }
}
