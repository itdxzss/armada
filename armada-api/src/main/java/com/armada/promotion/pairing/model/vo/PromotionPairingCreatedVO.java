package com.armada.promotion.pairing.model.vo;

/**
 * 新建配对会话结果。
 *
 * @param sessionToken 只返回一次的会话访问令牌
 * @param status 当前状态
 * @param expiresAt 过期时间(epoch 毫秒)
 */
public record PromotionPairingCreatedVO(String sessionToken, String status, Long expiresAt) {
}
