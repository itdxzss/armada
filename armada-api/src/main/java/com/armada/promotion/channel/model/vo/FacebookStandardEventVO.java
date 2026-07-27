package com.armada.promotion.channel.model.vo;

/**
 * Facebook 标准事件目录项。
 *
 * @param code 提交和持久化使用的官方事件代码
 * @param nameZh 中文展示名
 * @param nameEn 英文展示名
 */
public record FacebookStandardEventVO(String code, String nameZh, String nameEn) {
}
