package com.armada.group.model;

/** 群链接逐行导入结果。 */
public enum GroupLinkImportResult {
    /** 成功:新链接,已入 group_link。 */
    SUCCESS(1),
    /** 收编:已存在的链接,归到本次目标分组(改 label_id 或复活)。 */
    ADOPTED(2),
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
