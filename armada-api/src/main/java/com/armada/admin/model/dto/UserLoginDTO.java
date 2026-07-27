package com.armada.admin.model.dto;

/** 用户名、密码和图片验证码登录入参。 */
public record UserLoginDTO(String username, String password, String captchaId, String captchaCode) {
}
