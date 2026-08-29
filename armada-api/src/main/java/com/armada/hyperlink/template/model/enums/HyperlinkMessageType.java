package com.armada.hyperlink.template.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.Arrays;

/** 超链消息类型，数据库与 API 共用固定数值。 */
public enum HyperlinkMessageType {

    /** 单图文链接预览，一期开放。 */
    SINGLE_LINK_PREVIEW(1, true),

    /** 双图文兼容位，一期明确拒绝写入。 */
    DOUBLE_IMAGE_TEXT(2, false),

    /** 普通 URL 按钮，一期开放。 */
    NORMAL_BUTTON(3, true),

    /** 卡片 URL 按钮，一期开放。 */
    CARD_BUTTON(4, true);

    /** API 与数据库共用数值。 */
    private final int code;
    /** 是否允许在一期接口中使用。 */
    private final boolean phaseOneSupported;

    HyperlinkMessageType(int code, boolean phaseOneSupported) {
        this.code = code;
        this.phaseOneSupported = phaseOneSupported;
    }

    public int code() {
        return code;
    }

    public boolean phaseOneSupported() {
        return phaseOneSupported;
    }

    /** 按 API 数值解析消息类型，未知值返回参数错误。 */
    public static HyperlinkMessageType fromCode(Integer code) {
        return Arrays.stream(values())
                .filter(value -> Integer.valueOf(value.code).equals(code))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION, "消息类型只支持 1、2、3、4"));
    }
}
