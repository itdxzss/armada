package com.armada.hyperlink.template.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 超链按钮类型。 */
public enum HyperlinkButtonType {

    /** 打开绝对 HTTP/HTTPS URL 的 CTA 按钮。 */
    CTA_URL;

    /** 按冻结的 API 字符串读取；未知类型留给业务校验返回 40001。 */
    @JsonCreator
    public static HyperlinkButtonType fromApiValue(String value) {
        return CTA_URL.name().equals(value) ? CTA_URL : null;
    }

    /** 输出冻结的 API 字符串。 */
    @JsonValue
    public String apiValue() {
        return name();
    }
}
