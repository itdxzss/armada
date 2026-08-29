package com.armada.hyperlink.task.model.dto;

/**
 * 任务 Save wire 的唯一 CTA 按钮。
 *
 * @param type 固定 CTA_URL
 * @param displayText 展示文案
 * @param url 原始 HTTP(S) 地址
 * @param useShortLink 是否启用深度追踪
 */
public record HyperlinkTaskButtonDTO(
        String type,
        String displayText,
        String url,
        Boolean useShortLink) {
}
