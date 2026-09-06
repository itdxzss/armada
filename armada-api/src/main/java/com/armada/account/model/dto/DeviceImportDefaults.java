package com.armada.account.model.dto;

/**
 * 服务端令牌选中的导入默认值，不包含令牌或账号凭据。
 * @param tenantId 令牌绑定的租户，不接受手机覆盖
 * @param metadata 固定全参格式及服务端分组、机型、类型、IP 策略
 */
public record DeviceImportDefaults(long tenantId, AccountImportDTO metadata) {
}
