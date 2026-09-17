package com.armada.account.model.dto;

/** 设备凭证仅绑定租户和设备；每笔采购许可由控端管理。 */
public record DeviceRegistrationIdentity(long tenantId, String deviceId) { }
