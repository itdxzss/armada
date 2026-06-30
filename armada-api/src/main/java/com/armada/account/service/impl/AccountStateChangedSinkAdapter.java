package com.armada.account.service.impl;

import com.armada.account.service.AccountStateChangedEvent;
import com.armada.account.service.AccountStateEventService;
import com.armada.platform.kafka.consumer.account.ProtocolAccountStateChangedEvent;
import com.armada.platform.kafka.consumer.account.ProtocolAccountStateChangedSink;
import org.springframework.stereotype.Service;

/**
 * 协议账号状态变更事件到 account 域服务的 adapter。
 *
 * <p>Kafka consumer 位于 platform.kafka,不直接依赖账号域实现细节。
 * 本 adapter 由 account 域实现 platform 定义的 sink 接口,把平台层事件转换为账号域入参。</p>
 */
@Service
public class AccountStateChangedSinkAdapter implements ProtocolAccountStateChangedSink {

    private final AccountStateEventService service;

    /**
     * 创建账号状态变更 adapter。
     *
     * @param service 账号状态事件落库服务
     */
    public AccountStateChangedSinkAdapter(AccountStateEventService service) {
        this.service = service;
    }

    /**
     * 处理协议账号状态变更事件。
     *
     * @param event platform.kafka 已解析的状态变更事件
     */
    @Override
    public void handleStateChanged(ProtocolAccountStateChangedEvent event) {
        service.applyStateChanged(new AccountStateChangedEvent(
                event.tenantId(),
                event.accountId(),
                event.protocolAccountId(),
                event.from(),
                event.to(),
                event.occurredAt(),
                event.semantic(),
                event.rawCode()));
    }
}
