package com.armada.promotion.pairing.model.vo;

/**
 * 配对会话公开状态，不包含协议凭据和代理信息。
 *
 * @param status 当前状态
 * @param pairingCode 等待用户确认时展示的随机配对码
 * @param expiresAt 过期时间(epoch 毫秒)
 * @param accountId 成功落库后的账号 ID
 * @param errorCode 脱敏失败码
 * @param errorMessage 可展示失败原因
 */
public record PromotionPairingStatusVO(
        String status,
        String pairingCode,
        Long expiresAt,
        Long accountId,
        String errorCode,
        String errorMessage) {
}
