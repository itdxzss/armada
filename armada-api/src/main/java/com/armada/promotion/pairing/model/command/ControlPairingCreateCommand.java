package com.armada.promotion.pairing.model.command;

/** 登录控台后发起的认证码导号命令。 */
public record ControlPairingCreateCommand(
        String phone,
        Long accountGroupId,
        String remark,
        Long ownerUserId) {
}
