package com.armada.hyperlink.template.model;

import com.armada.hyperlink.template.model.enums.HyperlinkButtonType;

/**
 * 超链消息按钮共享结构。
 *
 * @param type 按钮类型，一期仅 CTA_URL
 * @param displayText 按钮展示文字
 * @param targetValue 原始目标 URL
 * @param useShortLink 未来任务是否默认生成短链
 * @param sort 按钮顺序，一期固定为 1
 */
public record HyperlinkButton(
        HyperlinkButtonType type,
        String displayText,
        String targetValue,
        Boolean useShortLink,
        Integer sort) {
}
