package com.armada.account.model.dto;

/**
 * 服务端令牌选中的导入默认值，不包含令牌或账号凭据。
 * @param tenantId 令牌绑定的租户，不接受手机覆盖
 * @param metadata 固定全参格式及服务端机型、类型、IP 策略；分组在上传时由手机选择
 */
public record DeviceImportDefaults(long tenantId, AccountImportDTO metadata) {
}
