package com.armada.promotion.pairing.model.vo;

/** 控台认证码导号会话状态。 */
public record ControlPairingStatusVO(
        String status,
        String pairingCode,
        long expiresAt,
        Long accountId,
        String errorCode,
        String errorMessage) {
}
