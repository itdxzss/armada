package com.armada.marketing.asset.model.dto;

import java.util.List;

/**
 * 图片素材名称与标签更新请求。
 *
 * @param assetName trim 后必填的素材业务名称
 * @param tags 完整标签集合，服务端统一归一化
 */
public record ResourceAssetUpdateDTO(String assetName, List<String> tags) {
}
