package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingFixedAccountGroupMetadataPortTest {

    @Test
    void routesMetadataOnlyToTheAccountBackend() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        RecordingBackend android = new RecordingBackend(ProtocolBackend.ANDROID);
        RoutingFixedAccountGroupMetadataPort port =
                new RoutingFixedAccountGroupMetadataPort(List.of(web, android));
        ProtocolAccountRef account = account(ProtocolBackend.ANDROID);

        GroupMetadataResult result = port.getMetadata(account, "120363android@g.us");

        assertThat(result.subject()).isEqualTo("ANDROID-历史群");
        assertThat(web.calls).isZero();
        assertThat(android.calls).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateAndMissingBackendRegistrations() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        assertThatThrownBy(() -> new RoutingFixedAccountGroupMetadataPort(List.of(web, web)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEB");

        RoutingFixedAccountGroupMetadataPort port =
                new RoutingFixedAccountGroupMetadataPort(List.of(web));
        assertThatThrownBy(() -> port.getMetadata(null, "120363android@g.us"))
                .isInstanceOfSatisfying(ProtocolException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> port.getMetadata(
                account(ProtocolBackend.ANDROID), "120363android@g.us"))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.UNSUPPORTED_BACKEND);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("group.metadata.get");
                });
    }

    private static ProtocolAccountRef account(ProtocolBackend backend) {
        return new ProtocolAccountRef(7L, backend, "acc_7", "919000000001");
    }

    private static final class RecordingBackend implements FixedAccountGroupMetadataBackend {
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
        public GroupMetadataResult getMetadata(
                ProtocolAccountRef account,
                String groupJid) {
            calls++;
            return new GroupMetadataResult(
                    groupJid,
                    backend + "-历史群",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    "协议未提供 inviteViaLink 设置状态",
                    false,
                    false,
                    List.of());
        }
    }
}
