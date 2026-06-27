package com.armada.group.model.enums;

/** 群链接导入成功类型。 */
public enum GroupLinkImportSuccessType {
    INSERTED(1, "新增"),
    ADOPTED(2, "收编已有群");

    private final int code;
    private final String label;

    GroupLinkImportSuccessType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static GroupLinkImportSuccessType fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (GroupLinkImportSuccessType value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知导入成功类型: " + code);
    }
}
