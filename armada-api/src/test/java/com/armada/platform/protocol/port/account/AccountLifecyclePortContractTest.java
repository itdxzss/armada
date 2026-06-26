package com.armada.platform.protocol.port.account;

import com.armada.platform.protocol.port.AccountLifecyclePort;
import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.protocol.model.command.OnlineCommand;
import com.armada.platform.protocol.model.command.ProxyDescriptor;
import com.armada.platform.protocol.model.result.OnlineAccepted;
import com.armada.platform.protocol.model.result.OnlineRouting;
import com.armada.platform.protocol.model.result.StateSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;

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
        AccountLifecyclePort port = (protocolAccountId, onlineCommand) -> accepted;

        assertThat(command.proxy().sessionId()).isEqualTo("session-acc-001");
        assertThat(port.online("acc_001", command)).isSameAs(accepted);
        assertThat(accepted.protocolAccountId()).isEqualTo("acc_001");
        assertThat(accepted.stateSource()).isEqualTo(StateSource.MANUAL_REFRESH);
        assertThat(accepted.routing().local()).isTrue();
    }
}
