package com.armada.marketing.asset.model.dto;

import java.util.List;

/** @param assetIds 要整体移组的素材 ID @param groupId 目标分组，null 表示未分组 */
public record ResourceAssetMoveDTO(List<Long> assetIds, Long groupId) {
}
