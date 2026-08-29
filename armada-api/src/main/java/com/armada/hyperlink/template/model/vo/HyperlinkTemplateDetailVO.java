package com.armada.hyperlink.template.model.vo;

import com.armada.hyperlink.template.model.HyperlinkButton;
import java.util.List;

/**
 * 超链模板完整详情。
 *
 * @param id 模板 ID
 * @param name 模板名称
 * @param remark 备注
 * @param schemaVersion 消息契约版本
 * @param messageType 消息类型
 * @param title 标题
 * @param content 正文
 * @param linkDescription 单图文链接描述
 * @param promotionLink 单图文推广链接
 * @param buttons 按钮数组
 * @param cardText 卡片底部文字
 * @param linkPreviewAssetId 链接预览图素材 ID
 * @param linkPreviewAssetUrl 链接预览图读取地址
 * @param bodyMainAssetId 正文主图素材 ID
 * @param bodyMainAssetUrl 正文主图读取地址
 * @param taskRefCount 任务引用数，一期固定为 0
 * @param version 版本
 * @param createdBy 创建人
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record HyperlinkTemplateDetailVO(
        Long id,
        String name,
        String remark,
        Integer schemaVersion,
        Integer messageType,
        String title,
        String content,
        String linkDescription,
        String promotionLink,
        List<HyperlinkButton> buttons,
        String cardText,
        Long linkPreviewAssetId,
        String linkPreviewAssetUrl,
        Long bodyMainAssetId,
        String bodyMainAssetUrl,
        long taskRefCount,
        Integer version,
        Long createdBy,
        Long createdAt,
        Long updatedAt) {
}
