package com.armada.account.service.impl;

import com.armada.account.service.AccountOnlinePlan;
import com.armada.account.service.AccountOnlineService;
import com.armada.platform.protocol.port.account.AccountLifecyclePort;
import com.armada.platform.protocol.port.account.command.CredentialFormat;
import com.armada.platform.protocol.port.account.command.OnlineCommand;
import com.armada.platform.protocol.port.account.command.ProxyDescriptor;
import com.armada.platform.protocol.port.account.result.OnlineAccepted;
import com.armada.platform.proxy.ProxyEndpoint;
import com.armada.platform.proxy.ProxyResolver;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * 账号上线编排实现。
 *
 * <p>当前口只做薄编排:校验上线计划、解析代理、调用协议端口。
 * 真正 ONLINE 终态等待后续 Kafka 状态回写口处理。</p>
 */
@Service
public class AccountOnlineServiceImpl implements AccountOnlineService {

    private final ProxyResolver proxyResolver;
    private final AccountLifecyclePort accountLifecyclePort;

    public AccountOnlineServiceImpl(ProxyResolver proxyResolver,
                                    AccountLifecyclePort accountLifecyclePort) {
        this.proxyResolver = proxyResolver;
        this.accountLifecyclePort = accountLifecyclePort;
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现步骤:
     * ① 校验 plan 中的协议账号 ID、凭据格式、凭据原文和代理端点;
     * ② 通过 ProxyResolver 转成协议层需要的 ProxyDescriptor;
     * ③ 组装 OnlineCommand 并委托 AccountLifecyclePort.online。</p>
     */
    @Override
    public OnlineAccepted online(AccountOnlinePlan plan) {
        if (plan == null) {
            throw validation("账号上线计划不能为空");
        }
        String protocolAccountId = requireText(plan.protocolAccountId(), "协议账号 ID 不能为空");
        CredentialFormat credentialFormat = requireCredentialFormat(plan.credentialFormat());
        String credentialJson = requireCredentialJson(plan.credentialJson());
        ProxyEndpoint proxyEndpoint = requireProxyEndpoint(plan.proxyEndpoint());

        ProxyDescriptor proxy = proxyResolver.resolve(proxyEndpoint);
        OnlineCommand command = new OnlineCommand(credentialFormat, credentialJson, proxy);
        return accountLifecyclePort.online(protocolAccountId, command);
    }

    private static CredentialFormat requireCredentialFormat(CredentialFormat credentialFormat) {
        if (credentialFormat == null) {
            throw validation("凭据格式不能为空");
        }
        return credentialFormat;
    }

    private static String requireCredentialJson(String credentialJson) {
        if (credentialJson == null || credentialJson.isBlank()) {
            throw validation("账号凭据不能为空");
        }
        return credentialJson;
    }

    private static ProxyEndpoint requireProxyEndpoint(ProxyEndpoint proxyEndpoint) {
        if (proxyEndpoint == null) {
            throw validation("代理端点不能为空");
        }
        return proxyEndpoint;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw validation(message);
        }
        return value.trim();
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }
}
