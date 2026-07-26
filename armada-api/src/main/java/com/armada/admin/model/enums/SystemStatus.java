package com.armada.admin.model.enums;

/** 系统管理统一状态。 */
public enum SystemStatus {
    DISABLED(0),
    ENABLED(1);

    private final int code;

    SystemStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
