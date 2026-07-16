package com.armada.group.model.vo;

/**
 * 可执行群协议操作的在线在群账号。
 *
 * @param accountId         Armada 本地账号 ID
 * @param protocolAccountId 协议层账号句柄
 */
public record GroupExecutionAccount(Long accountId, String protocolAccountId) {
}
