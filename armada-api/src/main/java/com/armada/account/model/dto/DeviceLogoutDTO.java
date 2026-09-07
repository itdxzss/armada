package com.armada.account.model.dto;

/**
 * 手机确认官方退出后提交的交接凭条，不包含账号凭据。
 * @param batchId 已成功上传且属于当前令牌租户的单账号批次
 */
public record DeviceLogoutDTO(Long batchId) {
}
