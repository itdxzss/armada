package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolAccountRuntimeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingAccountRuntimeStatusPortTest {

    @Test
    void routesStatusOnlyToTheSelectedBackend() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        RecordingBackend android = new RecordingBackend(ProtocolBackend.ANDROID);
        RoutingAccountRuntimeStatusPort port =
                new RoutingAccountRuntimeStatusPort(List.of(web, android));
        ProtocolAccountRef account = account(ProtocolBackend.ANDROID);

        ProtocolAccountRuntimeStatus result = port.status(account);

        assertThat(result.online()).isTrue();
        assertThat(web.lastAccount).isNull();
        assertThat(android.lastAccount).isSameAs(account);
    }

    @Test
    void rejectsDuplicateStatusBackendRegistrations() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        assertThatThrownBy(() -> new RoutingAccountRuntimeStatusPort(List.of(web, web)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEB");
    }

    @Test
    void reportsCanonicalContextWhenStatusBackendIsMissing() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        RoutingAccountRuntimeStatusPort port =
                new RoutingAccountRuntimeStatusPort(List.of(web));
        assertThatThrownBy(() -> port.status(account(ProtocolBackend.ANDROID)))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.UNSUPPORTED_BACKEND);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("account.status");
                    assertThat(ex.operationId()).contains("account:10");
                });
    }

    private static ProtocolAccountRef account(ProtocolBackend backend) {
        return new ProtocolAccountRef(10L, backend, "acc_919000000001", "919000000001");
    }

    private static final class RecordingBackend implements AccountRuntimeStatusBackend {
        private final ProtocolBackend backend;
        private ProtocolAccountRef lastAccount;

        private RecordingBackend(ProtocolBackend backend) {
            this.backend = backend;
        }

        @Override
        public ProtocolBackend backend() {
            return backend;
        }

        @Override
        public ProtocolAccountRuntimeStatus status(ProtocolAccountRef account) {
            lastAccount = account;
            return new ProtocolAccountRuntimeStatus("ONLINE");
        }
    }
}
