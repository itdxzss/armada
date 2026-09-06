package com.armada.promotion.pairing.model.vo;

/** 控台认证码导号会话创建结果。 */
public record ControlPairingCreatedVO(Long sessionId, String status, long expiresAt) {
}
