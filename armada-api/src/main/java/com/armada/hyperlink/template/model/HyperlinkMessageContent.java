package com.armada.hyperlink.template.model;

import java.util.List;

/**
 * 模板与未来超链任务内容快照共用的消息结构。
 *
 * @param schemaVersion 消息契约版本，一期固定为 1
 * @param messageType 消息类型数值
 * @param title 消息标题
 * @param content 消息正文
 * @param linkDescription 单图文链接描述
 * @param promotionLink 单图文原始推广链接
 * @param buttons 按钮数组
 * @param cardText 卡片正文，卡片按钮发送时映射到协议 Body
 * @param linkPreviewAssetId 链接预览图稳定素材 ID
 * @param bodyMainAssetId 正文主图或卡片头图稳定素材 ID
 */
public record HyperlinkMessageContent(
        Integer schemaVersion,
        Integer messageType,
        String title,
        String content,
        String linkDescription,
        String promotionLink,
        List<HyperlinkButton> buttons,
        String cardText,
        Long linkPreviewAssetId,
        Long bodyMainAssetId) {
}
