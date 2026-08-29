package com.armada.hyperlink.task.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/** 超链任务运行模式及数据库码。 */
public enum HyperlinkTaskMode {
    /** 一次性即时任务。 */
    INSTANT("instant", 1),
    /** 预发布，允许新发信账号加入首轮。 */
    ROLLING("rolling", 2),
    /** 周期选择账号并分配剩余 recipient。 */
    CYCLE("cycle", 3);

    private final String api;
    private final int code;

    HyperlinkTaskMode(String api, int code) {
        this.api = api;
        this.code = code;
    }

    public String api() { return api; }
    public int code() { return code; }

    /** 按 API 值解析任务模式。 */
    public static HyperlinkTaskMode fromApi(String value) {
        for (HyperlinkTaskMode mode : values()) {
            if (mode.api.equals(value)) {
                return mode;
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "taskMode 非法");
    }
}
