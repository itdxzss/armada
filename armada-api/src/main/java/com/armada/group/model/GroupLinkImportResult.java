package com.armada.group.model;

/** 群链接逐行导入结果。 */
public enum GroupLinkImportResult {
    /** 成功:新链接插入 group_link;或复活之前软删的同 url 链接到本分组。 */
    SUCCESS(1, "成功"),
    /** 已存在:同 url 已活跃存在(在某分组),不导入、原链接不动。换组请走「迁移分组」。 */
    EXISTS(2, "已存在"),
    /** 重复:本批次内同 url 出现多次,跳过。 */
    DUPLICATE(3, "批内重复"),
    /** 格式错误:不符合 WhatsApp 邀请链接格式。 */
    FORMAT_ERROR(4, "格式错误");

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
