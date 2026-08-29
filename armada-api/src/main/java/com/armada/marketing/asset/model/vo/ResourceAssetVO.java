package com.armada.marketing.asset.model.vo;

import java.util.List;

/**
 * 图片素材管理和选择器共用的稳定响应。
 *
 * @param id 素材文件 ID
 * @param assetName 素材业务名称
 * @param contentUrl 鉴权图片内容路径
 * @param tags 标签集合
 * @param sizeBytes 图片字节数
 * @param width 图片宽度；历史图片可为空
 * @param height 图片高度；历史图片可为空
 * @param referenceCount 有效模板或任务的去重引用数
 * @param createdBy 上传人用户 ID；历史图片可为空
 * @param createdAt 创建时间，epoch 毫秒
 * @param updatedAt 更新时间，epoch 毫秒
 */
public record ResourceAssetVO(
        Long id,
        String assetName,
        String contentUrl,
        List<String> tags,
        Long sizeBytes,
        Integer width,
        Integer height,
        long referenceCount,
        Long createdBy,
        Long createdAt,
        Long updatedAt) {
}
