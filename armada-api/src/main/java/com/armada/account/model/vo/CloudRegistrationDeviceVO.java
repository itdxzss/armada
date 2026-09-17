package com.armada.account.model.vo;

/** 已绑定云手机的公开目录信息；不代表设备在线或已就绪。 */
public record CloudRegistrationDeviceVO(String deviceId, String cloudPhoneId, String displayName) { }
