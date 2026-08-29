package com.armada.hyperlink.task.model.dto;

import java.util.List;

/** 任务 Save wire 的消息内容；消息类型只允许出现在外层。 */
public record HyperlinkTaskMessageContentDTO(
        Long linkPreviewAssetId,
        String title,
        String linkDescription,
        String promotionLink,
        Long bodyMainAssetId,
        String content,
        String cardText,
        List<HyperlinkTaskButtonDTO> buttons) {
}
