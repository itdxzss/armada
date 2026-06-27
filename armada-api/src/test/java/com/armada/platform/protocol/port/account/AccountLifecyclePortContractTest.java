package com.armada.platform.protocol.port.account;

import com.armada.platform.protocol.port.AccountLifecyclePort;
import com.armada.platform.protocol.model.command.BatchOnlineCommand;
import com.armada.platform.protocol.model.command.BatchOnlineCommandItem;
import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.protocol.model.command.OnlineCommand;
import com.armada.platform.protocol.model.command.ProxyDescriptor;
import com.armada.platform.protocol.model.result.BatchOnlineAccepted;
import com.armada.platform.protocol.model.result.BatchOnlineItemResult;
import com.armada.platform.protocol.model.result.BatchOnlineResultStatus;
import com.armada.platform.protocol.model.result.BatchOnlineSummary;
import com.armada.platform.protocol.model.result.OnlineAccepted;
import com.armada.platform.protocol.model.result.OnlineRouting;
import com.armada.platform.protocol.model.result.StateSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccountLifecyclePortContractTest {

    @Test
    void onlineContractUsesPortScopedTypesAndProtocolAccountId() {
        ProxyDescriptor proxy = new ProxyDescriptor(
                "socks5",
                "socks5://user:pass@proxy.example:1080",
                "session-acc-001",
                "US");
        OnlineCommand command = new OnlineCommand(
                CredentialFormat.BAILEYS_JSON,
                "{\"creds\":{}}",
                proxy);

        OnlineRouting routing = new OnlineRouting("worker-a", null, "worker-a", true);
        OnlineAccepted accepted = new OnlineAccepted(
                "acc_001",
                true,
                StateSource.MANUAL_REFRESH,
                Instant.parse("2026-06-26T00:00:00Z"),
                routing);
        AccountLifecyclePort port = new AccountLifecyclePort() {
            @Override
            public OnlineAccepted online(String protocolAccountId, OnlineCommand command) {
                return accepted;
            }

            @Override
            public BatchOnlineAccepted onlineBatch(BatchOnlineCommand command) {
                throw new UnsupportedOperationException();
            }
        };

        assertThat(command.proxy().sessionId()).isEqualTo("session-acc-001");
        assertThat(port.online("acc_001", command)).isSameAs(accepted);
        assertThat(accepted.protocolAccountId()).isEqualTo("acc_001");
        assertThat(accepted.stateSource()).isEqualTo(StateSource.MANUAL_REFRESH);
        assertThat(accepted.routing().local()).isTrue();
    }

    @Test
    void onlineBatchContractUsesPortScopedTypesAndProtocolAccountIds() {
        OnlineCommand onlineCommand = new OnlineCommand(
                CredentialFormat.BAILEYS_JSON,
                "{\"creds\":{},\"keys\":{}}",
                new ProxyDescriptor("socks5", "socks5://proxy.internal:1080", "sticky-001", "IN"));
        BatchOnlineCommand command = new BatchOnlineCommand(
                List.of(new BatchOnlineCommandItem("acc_001", onlineCommand)),
                60_000);
        BatchOnlineAccepted accepted = new BatchOnlineAccepted(
                Instant.parse("2026-06-27T10:00:00Z"),
                120L,
                new BatchOnlineSummary(1, 1, 0, 1, 0, 0, 0),
                List.of(new BatchOnlineItemResult("acc_001", BatchOnlineResultStatus.ACCEPTED, null, null)),
                List.of());
        AccountLifecyclePort port = new AccountLifecyclePort() {
            @Override
            public OnlineAccepted online(String protocolAccountId, OnlineCommand command) {
                throw new UnsupportedOperationException();
            }

            @Override
            public BatchOnlineAccepted onlineBatch(BatchOnlineCommand command) {
                return accepted;
            }
        };

        assertThat(port.onlineBatch(command)).isSameAs(accepted);
    }
}
