package com.armada.admin.model.enums;

/** 菜单节点类型。 */
public enum MenuType {
    DIRECTORY("D"),
    MENU("M"),
    BUTTON("B");

    private final String code;

    MenuType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
