package com.armada.account.service.impl;

import com.armada.account.service.AccountOnlinePlan;
import com.armada.account.service.AccountOnlineService;
import com.armada.platform.protocol.model.command.BatchOnlineCommand;
import com.armada.platform.protocol.model.command.BatchOnlineCommandItem;
import com.armada.platform.protocol.port.AccountLifecyclePort;
import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.protocol.model.command.OnlineCommand;
import com.armada.platform.protocol.model.command.ProxyDescriptor;
import com.armada.platform.protocol.model.result.BatchOnlineAccepted;
import com.armada.platform.protocol.model.result.OnlineAccepted;
import com.armada.platform.proxy.ProxyEndpoint;
import com.armada.platform.proxy.ProxyResolver;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.List;
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
        // 入口只接收上层已经准备好的上线计划;账号查库、凭据加载、代理分配不在本服务里做。
        if (plan == null) {
            throw validation("账号上线计划不能为空");
        }

        // 逐项校验并归一化必填字段。这里在调用协议层前失败,避免把不完整命令投递到协议服务。
        String protocolAccountId = requireText(plan.protocolAccountId(), "协议账号 ID 不能为空");
        CredentialFormat credentialFormat = requireCredentialFormat(plan.credentialFormat());
        String credentialJson = requireCredentialJson(plan.credentialJson());
        ProxyEndpoint proxyEndpoint = requireProxyEndpoint(plan.proxyEndpoint());

        // 代理端点是 armada 内部模型;协议层需要 ProxyDescriptor,转换逻辑集中放在 ProxyResolver。
        ProxyDescriptor proxy = proxyResolver.resolve(proxyEndpoint);

        // 这里只组装并投递 online 命令。返回值表示协议层已受理,不代表账号已经 ONLINE。
        // 本地账号在线状态仍然等待协议层异步回填 Kafka 后再更新。
        OnlineCommand command = new OnlineCommand(credentialFormat, credentialJson, proxy);
        return accountLifecyclePort.online(protocolAccountId, command);
    }

    /**
     * {@inheritDoc}
     *
     * <p>这里仍然只做薄编排:逐个校验 plan、解析代理为协议层 proxy 描述,
     * 然后组装一次 {@link BatchOnlineCommand} 投递给协议端口。</p>
     */
    @Override
    public BatchOnlineAccepted onlineBatch(List<AccountOnlinePlan> plans, int maxWaitMs) {
        if (plans == null || plans.isEmpty()) {
            throw validation("批量上线计划不能为空");
        }
        if (maxWaitMs <= 0) {
            throw validation("批量上线等待时间必须大于 0");
        }
        List<BatchOnlineCommandItem> items = plans.stream()
                .map(this::toBatchItem)
                .toList();
        return accountLifecyclePort.onlineBatch(new BatchOnlineCommand(items, maxWaitMs));
    }

    private BatchOnlineCommandItem toBatchItem(AccountOnlinePlan plan) {
        if (plan == null) {
            throw validation("批量上线计划不能为空");
        }
        String protocolAccountId = requireText(plan.protocolAccountId(), "协议账号 ID 不能为空");
        CredentialFormat credentialFormat = requireCredentialFormat(plan.credentialFormat());
        String credentialJson = requireCredentialJson(plan.credentialJson());
        ProxyEndpoint proxyEndpoint = requireProxyEndpoint(plan.proxyEndpoint());
        ProxyDescriptor proxy = proxyResolver.resolve(proxyEndpoint);
        return new BatchOnlineCommandItem(
                protocolAccountId,
                new OnlineCommand(credentialFormat, credentialJson, proxy));
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
