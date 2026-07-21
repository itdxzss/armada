package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ContactSaveCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingContactPortTest {

    @Test
    void routesOnlyToTheBackendSelectedByTheAccountReference() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        RecordingBackend android = new RecordingBackend(ProtocolBackend.ANDROID);
        RoutingContactPort port = new RoutingContactPort(List.of(web, android));
        ContactSaveCommand command = command(ProtocolBackend.ANDROID);

        port.save(command);

        assertThat(web.lastCommand).isNull();
        assertThat(android.lastCommand).isSameAs(command);
    }

    @Test
    void rejectsDuplicateAndMissingBackendRegistrations() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        assertThatThrownBy(() -> new RoutingContactPort(List.of(web, web)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEB");

        RoutingContactPort port = new RoutingContactPort(List.of(web));
        assertThatThrownBy(() -> port.save(command(ProtocolBackend.ANDROID)))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.UNSUPPORTED_BACKEND);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("contact.save");
                    assertThat(ex.operationId()).contains("group-creation-marketing-item:11");
                });
    }

    private static ContactSaveCommand command(ProtocolBackend backend) {
        return new ContactSaveCommand(
                new ProtocolAccountRef(7L, backend, "acc_7", "919000000001"),
                "919000000002",
                "919000000002",
                "group-creation-marketing-item:11");
    }

    private static final class RecordingBackend implements ContactBackend {
        private final ProtocolBackend backend;
        private ContactSaveCommand lastCommand;

        private RecordingBackend(ProtocolBackend backend) {
            this.backend = backend;
        }

        @Override
        public ProtocolBackend backend() {
            return backend;
        }

        @Override
        public void save(ContactSaveCommand command) {
            lastCommand = command;
        }
    }
}
