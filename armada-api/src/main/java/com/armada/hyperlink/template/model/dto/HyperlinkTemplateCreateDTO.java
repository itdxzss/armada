package com.armada.hyperlink.template.model.dto;

import com.armada.hyperlink.template.model.HyperlinkButton;
import java.util.List;

/**
 * 超链模板完整创建入参。
 *
 * @param name 模板名称
 * @param schemaVersion 消息契约版本
 * @param messageType 消息类型
 * @param title 标题
 * @param content 正文
 * @param linkDescription 单图文链接描述
 * @param promotionLink 单图文推广链接
 * @param buttons 按钮数组
 * @param cardText 卡片正文
 * @param linkPreviewAssetId 链接预览图素材 ID
 * @param bodyMainAssetId 正文主图素材 ID
 * @param remark 运营备注
 */
public record HyperlinkTemplateCreateDTO(
        String name,
        Integer schemaVersion,
        Integer messageType,
        String title,
        String content,
        String linkDescription,
        String promotionLink,
        List<HyperlinkButton> buttons,
        String cardText,
        Long linkPreviewAssetId,
        Long bodyMainAssetId,
        String remark) {
}
