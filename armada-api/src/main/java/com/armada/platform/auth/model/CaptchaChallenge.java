package com.armada.platform.auth.model;

/** 图片验证码响应数据。 */
public record CaptchaChallenge(String captchaId, String imageBase64, long expiresInSeconds) {
}
