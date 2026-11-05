package com.armada.account.model.dto;

/** @param accountType 账号类型：1 个人、2 商业；省略时沿用个人版，合法值由服务校验 */
public record SmsRegistrationStartDTO(Integer accountType) { }
