package com.armada.hyperlink.task.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/** 超链任务首轮启动方式。 */
public enum HyperlinkTaskStartMode {
    /** 准备完成后立即执行。 */
    NOW("now", 1),
    /** 准备完成后延迟指定分钟。 */
    SCHEDULED("scheduled", 2);

    private final String api;
    private final int code;

    HyperlinkTaskStartMode(String api, int code) {
        this.api = api;
        this.code = code;
    }

    public String api() { return api; }
    public int code() { return code; }

    /** 按 API 值解析启动方式。 */
    public static HyperlinkTaskStartMode fromApi(String value) {
        for (HyperlinkTaskStartMode mode : values()) {
            if (mode.api.equals(value)) {
                return mode;
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "startMode 非法");
    }
}
