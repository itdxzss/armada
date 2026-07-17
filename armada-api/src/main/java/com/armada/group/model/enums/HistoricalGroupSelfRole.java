package com.armada.group.model.enums;

import java.util.Locale;

/**
 * 协议层确认的操作账号自身群角色。
 */
public enum HistoricalGroupSelfRole {

    /** 群主。 */
    OWNER,

    /** 群管理员。 */
    ADMIN,

    /** 普通群成员。 */
    MEMBER;

    /**
     * 解析协议层自身角色。
     *
     * @param value 协议层角色文本
     * @return 已识别角色;空值或未知值返回 null
     */
    public static HistoricalGroupSelfRole fromProtocolValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
