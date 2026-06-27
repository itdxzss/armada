package com.armada.group.model;

/** 群链接逐行导入结果。 */
public enum GroupLinkImportResult {
    /** 成功:新增或收编已有群入口。 */
    SUCCESS(1, "成功"),
    /** 失败:重复或格式错误。 */
    FAILED(2, "失败");

    private final int code;

    /** 中文展示标签(出参 resultLabel 的唯一来源;后端算好,前端直接展示、不自行映射)。 */
    private final String label;

    GroupLinkImportResult(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() { return code; }

    /** @return 中文展示标签。 */
    public String label() { return label; }

    /** 按 code 反查;非法 code 抛 {@link IllegalArgumentException}(禁静默兜底)。 */
    public static GroupLinkImportResult fromCode(int code) {
        for (GroupLinkImportResult r : values()) {
            if (r.code == code) { return r; }
        }
        throw new IllegalArgumentException("未知导入结果码: " + code);
    }
}
