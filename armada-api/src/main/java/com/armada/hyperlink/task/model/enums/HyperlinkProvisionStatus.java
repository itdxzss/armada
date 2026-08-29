package com.armada.hyperlink.task.model.enums;

/** 超链任务受众、计费和首轮的准备状态。 */
public enum HyperlinkProvisionStatus {
    /** 仅保存，无需准备。 */
    NOT_REQUIRED(0),
    /** 分批准备中。 */
    PROCESSING(1),
    /** 准备完成，可被调度。 */
    READY(2),
    /** 准备失败，等待恢复或重新报价。 */
    FAILED(3);

    private final int code;

    HyperlinkProvisionStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** 按数据库码返回 API 枚举。 */
    public static HyperlinkProvisionStatus fromCode(Integer code) {
        for (HyperlinkProvisionStatus status : values()) {
            if (Integer.valueOf(status.code).equals(code)) {
                return status;
            }
        }
        return FAILED;
    }
}
