package com.armada.marketing.asset.model.vo;

import java.util.List;

/**
 * 当前租户仍被活动素材使用的标签候选。
 *
 * @param tags 按标签名稳定排序的候选
 */
public record ResourceAssetTagsVO(List<String> tags) {
}
