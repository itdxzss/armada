package com.armada.account.service;

import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.proxy.ProxyEndpoint;

/**
 * 单账号上线编排计划。
 *
 * <p>这是账号域发起上线前的内部输入模型:账号凭据来自 account_credential,
 * 代理端点由后续代理分配口提供。本模型只承接已选好的字段,不负责查库、分配或回收代理。</p>
 *
 * @param protocolAccountId 协议层账号句柄,例如 acc_&lt;wsPhone&gt;
 * @param credentialFormat  凭据格式
 * @param credentialJson    完整凭据原文,敏感字段,严禁写日志
 * @param proxyEndpoint     已选中的运行时代理端点
 */
public record AccountOnlinePlan(
        String protocolAccountId,
        CredentialFormat credentialFormat,
        String credentialJson,
        ProxyEndpoint proxyEndpoint
) {
}
