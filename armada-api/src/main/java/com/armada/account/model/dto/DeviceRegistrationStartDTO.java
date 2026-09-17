package com.armada.account.model.dto;

/** 显式确认本次许可与商家；空字符串表示按当前价档选择商家。 */
public record DeviceRegistrationStartDTO(String requestId, String providerId) { }
