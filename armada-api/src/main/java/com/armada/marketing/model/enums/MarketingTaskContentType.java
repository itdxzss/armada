package com.armada.marketing.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.springframework.util.StringUtils;

/**
 * 营销任务发送内容类型。
 */
public enum MarketingTaskContentType {

    TEMPLATE(1),
    TEXT(2);

    private final int code;

    MarketingTaskContentType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static MarketingTaskContentType fromRequest(String value, boolean hasTemplate, boolean hasText) {
        if (!StringUtils.hasText(value)) {
            if (hasTemplate && !hasText) {
                return TEMPLATE;
            }
            if (!hasTemplate && hasText) {
                return TEXT;
            }
            throw new BusinessException(ErrorCode.VALIDATION, "请选择营销模板或填写文本内容");
        }
        String normalized = value.trim().toUpperCase();
        return switch (normalized) {
            case "TEMPLATE", "1" -> TEMPLATE;
            case "TEXT", "2" -> TEXT;
            default -> throw new BusinessException(ErrorCode.VALIDATION, "发送内容类型不正确");
        };
    }
}
