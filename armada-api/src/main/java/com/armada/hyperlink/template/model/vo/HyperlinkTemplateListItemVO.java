package com.armada.hyperlink.template.model.vo;

/**
 * 超链模板列表项。
 *
 * @param id 模板 ID
 * @param name 模板名称
 * @param messageType 消息类型
 * @param title 标题
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
public record HyperlinkTemplateListItemVO(
        Long id,
        String name,
        Integer messageType,
        String title,
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
