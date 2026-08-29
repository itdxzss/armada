package com.armada.hyperlink.task.model.enums;

/** 任务级外部预约在本地的计费状态。 */
public enum HyperlinkBillingStatus {
    /** 外部操作处理中。 */
    PROCESSING(1),
    /** 报价金额已冻结。 */
    RESERVED(2),
    /** 已结算部分冻结金额。 */
    PARTIALLY_SETTLED(3),
    /** 冻结金额已全部结算。 */
    SETTLED(4),
    /** 未结算余额已释放。 */
    RELEASED(5),
    /** 外部操作失败，保留原待办以便恢复。 */
    FAILED(6);

    private final int code;

    HyperlinkBillingStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
