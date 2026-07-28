package com.armada.admin.model.dto;

/** 当前登录用户修改密码入参。 */
public record PasswordChangeDTO(String currentPassword, String newPassword) {
}
