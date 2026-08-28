package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.AccountContactSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingContactListPortTest {

    @Test
    void routesOnlyToTheBackendSelectedByTheAccountReference() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        RecordingBackend android = new RecordingBackend(ProtocolBackend.ANDROID);
        RoutingContactListPort port = new RoutingContactListPort(List.of(web, android));
        ProtocolAccountRef account = account(ProtocolBackend.ANDROID);

        AccountContactSnapshot snapshot = port.list(account);

        assertThat(web.lastAccount).isNull();
        assertThat(android.lastAccount).isSameAs(account);
        assertThat(snapshot.contacts()).hasSize(1);
        assertThat(snapshot.contacts().get(0).phone()).isEqualTo("919000000002");
    }

    @Test
    void rejectsDuplicateAndMissingBackendRegistrations() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        assertThatThrownBy(() -> new RoutingContactListPort(List.of(web, web)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEB");

        RoutingContactListPort port = new RoutingContactListPort(List.of(web));
        assertThatThrownBy(() -> port.list(account(ProtocolBackend.ANDROID)))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.UNSUPPORTED_BACKEND);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("contact.list");
                });
    }

    private static ProtocolAccountRef account(ProtocolBackend backend) {
        return new ProtocolAccountRef(7L, backend, "acc_7", "919000000001");
    }

    private static final class RecordingBackend implements ContactListBackend {
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
        public AccountContactSnapshot list(ProtocolAccountRef account) {
            lastAccount = account;
            return new AccountContactSnapshot(
                    List.of(new AccountContactSnapshot.Contact(
                            "919000000002",
                            "919000000002@s.whatsapp.net",
                            "张三",
                            "三",
                            "zhangsan",
                            null)),
                    1756345678901L);
        }
    }
}
