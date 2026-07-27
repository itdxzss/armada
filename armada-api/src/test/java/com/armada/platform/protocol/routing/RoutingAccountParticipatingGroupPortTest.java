package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.AccountGroupMetadataSummaryResult;
import com.armada.platform.protocol.model.result.AccountParticipatingGroupResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingAccountParticipatingGroupPortTest {

    @Test
    void routesBothReadsOnlyToTheAccountBackend() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        RecordingBackend android = new RecordingBackend(ProtocolBackend.ANDROID);
        RoutingAccountParticipatingGroupPort port =
                new RoutingAccountParticipatingGroupPort(List.of(web, android));
        ProtocolAccountRef account = account(ProtocolBackend.ANDROID);

        assertThat(port.listCurrent(account))
                .extracting(AccountParticipatingGroupResult.Group::groupJid)
                .containsExactly("120363android@g.us");
        assertThat(port.summarize(account, List.of("120363android@g.us"), 8))
                .extracting(AccountGroupMetadataSummaryResult::selfRole)
                .containsExactly("ADMIN");
        assertThat(web.calls).isZero();
        assertThat(android.calls).isEqualTo(2);
    }

    @Test
    void rejectsDuplicateAndMissingBackendRegistrations() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        assertThatThrownBy(() -> new RoutingAccountParticipatingGroupPort(List.of(web, web)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEB");

        RoutingAccountParticipatingGroupPort port =
                new RoutingAccountParticipatingGroupPort(List.of(web));
        assertThatThrownBy(() -> port.listCurrent(null))
                .isInstanceOfSatisfying(ProtocolException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> port.listCurrent(account(ProtocolBackend.ANDROID)))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.UNSUPPORTED_BACKEND);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("account.groups.current");
                });
    }

    private static ProtocolAccountRef account(ProtocolBackend backend) {
        return new ProtocolAccountRef(7L, backend, "acc_7", "919000000001");
    }

    private static final class RecordingBackend implements AccountParticipatingGroupBackend {
        private final ProtocolBackend backend;
        private int calls;

        private RecordingBackend(ProtocolBackend backend) {
            this.backend = backend;
        }

        @Override
        public ProtocolBackend backend() {
            return backend;
        }

        @Override
        public List<AccountParticipatingGroupResult.Group> listCurrent(
                ProtocolAccountRef account) {
            calls++;
            return List.of(new AccountParticipatingGroupResult.Group(
                    "120363android@g.us", "历史群", null, null, null, null));
        }

        @Override
        public List<AccountGroupMetadataSummaryResult> summarize(
                ProtocolAccountRef account,
                List<String> groupJids,
                int concurrency) {
            calls++;
            return List.of(new AccountGroupMetadataSummaryResult(
                    groupJids.get(0), true, null, "历史群", 10,
                    "ADMIN", null, false));
        }
    }
}
