package com.armada.marketing.asset.model.vo;

/**
 * 当前页单个素材的引用聚合结果。
 *
 * @param assetId 素材文件 ID
 * @param referenceCount 有效模板或任务的去重引用数
 */
public record ResourceAssetReferenceCountVO(Long assetId, Long referenceCount) {
}
