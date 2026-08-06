package com.armada.admin.model.dto;

/** 用户名和密码登录入参；图片验证码字段暂时保留供后续恢复。 */
public record UserLoginDTO(String username, String password, String captchaId, String captchaCode) {
}
