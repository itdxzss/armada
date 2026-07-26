package com.armada.promotion.pairing.model.dto;

/**
 * 落地页创建配对会话参数。
 *
 * @param phone 只包含数字的完整国际号码
 */
public record PromotionPairingCreateDTO(String phone) {
}
