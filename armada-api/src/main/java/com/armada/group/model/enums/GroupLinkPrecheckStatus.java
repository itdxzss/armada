package com.armada.group.model.enums;

/**
 * 群链接导入前预检测状态。
 */
public enum GroupLinkPrecheckStatus {

    /** 公开邀请页可识别出群资料,当前可作为可用链接展示。 */
    AVAILABLE("AVAILABLE", "可用"),

    /** 格式错误、公开页无法识别资料或抓取失败。 */
    UNAVAILABLE("UNAVAILABLE", "不可用");

    private final String code;
    private final String label;

    GroupLinkPrecheckStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }
}
