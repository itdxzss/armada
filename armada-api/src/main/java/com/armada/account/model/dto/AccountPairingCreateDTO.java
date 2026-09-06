package com.armada.account.model.dto;

/** 控台认证码导号请求。手机号必须是只含数字的完整国际号码。 */
public record AccountPairingCreateDTO(String phone, Long accountGroupId, String remark) {
}
