package com.armada.promotion.pairing.model.dto;

/**
 * 落地页创建配对会话参数。
 *
 * @param phone 只包含数字的完整国际号码
 * @param fbp Meta 浏览器标识 Cookie 值
 * @param fbc Meta 点击归因 Cookie 值或由 fbclid 构造的值
 * @param sourceUrl 用户访问的 HTTP/HTTPS 落地页地址
 */
public record PromotionPairingCreateDTO(
        String phone,
        String fbp,
        String fbc,
        String sourceUrl) {
}
