package com.armada.marketing.asset.model.vo;

/** @param id 分组 ID @param groupName 分组名称 @param assetCount 当前业务内未删除素材总数，不受分页和搜索条件影响 */
public record ResourceAssetGroupVO(Long id, String groupName, long assetCount) {
}
