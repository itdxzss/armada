package com.armada.platform.protocol.model.command;

/**
 * WhatsApp 手机号配对码请求。
 *
 * @param accountId Armada 为本次推广配对生成的一次性协议账号句柄
 * @param phone 只包含数字的完整国际号码
 * @param proxy 本次配对固定使用的代理出口
 */
public record PairingCodeCommand(String accountId, String phone, ProxyDescriptor proxy) {
}
