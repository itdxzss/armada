package com.armada.group.model.vo;

/**
 * 用于发起群成员实时查询的在线在群账号。
 *
 * @param accountId         Armada 本地账号 ID
 * @param protocolAccountId 协议层账号句柄
 */
public record GroupMemberQueryAccount(
        Long accountId,
        String protocolAccountId
) {
}
