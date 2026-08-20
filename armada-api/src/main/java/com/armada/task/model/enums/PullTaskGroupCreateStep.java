package com.armada.task.model.enums;

/** 新群模式建群阶段的持久化内部步骤。 */
public enum PullTaskGroupCreateStep {

    SELECT_ROLES(1, "准备建群账号"),
    CREATE_GROUP(2, "创建 WhatsApp 群"),
    PERSIST_CREATE_RESULT(3, "保存建群结果"),
    APPLY_PROFILE(4, "设置群资料"),
    CAPTURE_INVITE_LINK(5, "生成群邀请链接"),
    APPLY_BEFORE_PULL_SETTINGS(6, "应用拉人前群设置"),
    REGISTER_GROUP(7, "登记自建群");

    private final int code;
    private final String displayName;

    PullTaskGroupCreateStep(int code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public int code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public static PullTaskGroupCreateStep fromNullable(Integer code) {
        if (code == null) {
            return SELECT_ROLES;
        }
        for (PullTaskGroupCreateStep step : values()) {
            if (step.code == code) {
                return step;
            }
        }
        throw new IllegalArgumentException("未知建群步骤: " + code);
    }
}
