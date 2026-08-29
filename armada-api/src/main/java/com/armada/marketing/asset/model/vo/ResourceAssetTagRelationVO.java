package com.armada.marketing.asset.model.vo;

/**
 * 素材与标签的轻量关联行。
 *
 * @param fileId 素材文件 ID
 * @param tagName 标签名
 */
public record ResourceAssetTagRelationVO(Long fileId, String tagName) {
}
