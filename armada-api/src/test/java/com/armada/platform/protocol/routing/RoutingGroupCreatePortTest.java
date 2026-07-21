package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupCreateCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingGroupCreatePortTest {

    @Test
    void routesOnlyToTheBackendSelectedByTheAccountReference() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        RecordingBackend android = new RecordingBackend(ProtocolBackend.ANDROID);
        RoutingGroupCreatePort port = new RoutingGroupCreatePort(List.of(web, android));
        GroupCreateCommand command = command(ProtocolBackend.ANDROID);

        GroupCreateResult result = port.create(command);

        assertThat(result.groupJid()).isEqualTo("120363created@g.us");
        assertThat(web.lastCommand).isNull();
        assertThat(android.lastCommand).isSameAs(command);
    }

    @Test
    void rejectsDuplicateAndMissingBackendRegistrations() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        assertThatThrownBy(() -> new RoutingGroupCreatePort(List.of(web, web)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEB");

        RoutingGroupCreatePort port = new RoutingGroupCreatePort(List.of(web));
        assertThatThrownBy(() -> port.create(command(ProtocolBackend.ANDROID)))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.UNSUPPORTED_BACKEND);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("group.create");
                    assertThat(ex.operationId()).contains("group-creation-marketing-item:11");
                });
    }

    private static GroupCreateCommand command(ProtocolBackend backend) {
        return new GroupCreateCommand(
                new ProtocolAccountRef(7L, backend, "acc_7", "919000000001"),
                "活动群-1",
                List.of("919000000002"),
                true,
                "group-creation-marketing-item:11");
    }

    private static final class RecordingBackend implements GroupCreateBackend {
        private final ProtocolBackend backend;
        private GroupCreateCommand lastCommand;

        private RecordingBackend(ProtocolBackend backend) {
            this.backend = backend;
        }

        @Override
        public ProtocolBackend backend() {
            return backend;
        }

        @Override
        public GroupCreateResult create(GroupCreateCommand command) {
            lastCommand = command;
            return new GroupCreateResult("120363created@g.us", false, List.of());
        }
    }
}
