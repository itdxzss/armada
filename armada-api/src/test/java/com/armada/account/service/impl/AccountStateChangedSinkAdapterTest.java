package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.armada.account.service.AccountStateChangedEvent;
import com.armada.account.service.AccountStateEventService;
import com.armada.platform.kafka.consumer.account.ProtocolAccountStateChangedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 账号状态回写 adapter 单测。
 *
 * <p>验证 platform.kafka 入站事件会被转换为 account 域状态事件,不触碰数据库。</p>
 */
@ExtendWith(MockitoExtension.class)
class AccountStateChangedSinkAdapterTest {

    @Mock
    private AccountStateEventService service;

    @InjectMocks
    private AccountStateChangedSinkAdapter adapter;

    @Test
    void handleStateChanged_mapsPlatformEventToAccountStateService() {
        ProtocolAccountStateChangedEvent platformEvent = new ProtocolAccountStateChangedEvent(
                "evt-1",
                1L,
                100L,
                "acc_861800000001",
                "ONLINE",
                "NEED_REAUTH",
                1782626401000L,
                "NEED_REAUTH",
                403,
                "worker-a");

        adapter.handleStateChanged(platformEvent);

        ArgumentCaptor<AccountStateChangedEvent> captor = ArgumentCaptor.forClass(AccountStateChangedEvent.class);
        verify(service).applyStateChanged(captor.capture());
        AccountStateChangedEvent event = captor.getValue();
        assertThat(event.tenantId()).isEqualTo(1L);
        assertThat(event.accountId()).isEqualTo(100L);
        assertThat(event.protocolAccountId()).isEqualTo("acc_861800000001");
        assertThat(event.from()).isEqualTo("ONLINE");
        assertThat(event.to()).isEqualTo("NEED_REAUTH");
        assertThat(event.occurredAt()).isEqualTo(1782626401000L);
        assertThat(event.semantic()).isEqualTo("NEED_REAUTH");
        assertThat(event.rawCode()).isEqualTo(403);
    }
}
