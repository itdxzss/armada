package com.armada.group.model;

/** 群链接逐行导入结果。 */
public enum GroupLinkImportResult {
    /** 成功:新链接插入 group_link;或复活之前软删的同 url 链接到本分组。 */
    SUCCESS(1),
    /** 已存在:同 url 已活跃存在(在某分组),不导入、原链接不动。换组请走「迁移分组」。 */
    EXISTS(2),
    /** 重复:本批次内同 url 出现多次,跳过。 */
    DUPLICATE(3),
    /** 格式错误:不符合 WhatsApp 邀请链接格式。 */
    FORMAT_ERROR(4);

    private final int code;
    GroupLinkImportResult(int code) { this.code = code; }
    public int code() { return code; }

    /** 按 code 反查;非法 code 抛 {@link IllegalArgumentException}(禁静默兜底)。 */
    public static GroupLinkImportResult fromCode(int code) {
        for (GroupLinkImportResult r : values()) {
            if (r.code == code) { return r; }
        }
        throw new IllegalArgumentException("未知导入结果码: " + code);
    }
}
