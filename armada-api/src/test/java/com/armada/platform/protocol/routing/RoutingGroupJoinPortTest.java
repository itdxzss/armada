package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingGroupJoinPortTest {

    @Test
    void routesOnlyToTheBackendSelectedByTheAccountReference() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        RecordingBackend android = new RecordingBackend(ProtocolBackend.ANDROID);
        RoutingGroupJoinPort port = new RoutingGroupJoinPort(List.of(web, android));
        GroupJoinCommand command = command(ProtocolBackend.ANDROID);

        GroupJoinResult result = port.join(command);

        assertThat(result.outcome()).isEqualTo(GroupJoinOutcome.JOINED);
        assertThat(web.lastCommand).isNull();
        assertThat(android.lastCommand).isSameAs(command);
    }

    @Test
    void rejectsDuplicateAndMissingBackendRegistrations() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        assertThatThrownBy(() -> new RoutingGroupJoinPort(List.of(web, web)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEB");

        RoutingGroupJoinPort port = new RoutingGroupJoinPort(List.of(web));
        assertThatThrownBy(() -> port.join(command(ProtocolBackend.ANDROID)))
                .isInstanceOfSatisfying(ProtocolException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.UNSUPPORTED_BACKEND));
    }

    private static GroupJoinCommand command(ProtocolBackend backend) {
        return new GroupJoinCommand(
                new ProtocolAccountRef(10L, backend, "acc_919000000001", "919000000001"),
                "https://chat.whatsapp.com/ABC123",
                "join-task-result:77");
    }

    private static final class RecordingBackend implements GroupJoinBackend {
        private final ProtocolBackend backend;
        private GroupJoinCommand lastCommand;

        private RecordingBackend(ProtocolBackend backend) {
            this.backend = backend;
        }

        @Override
        public ProtocolBackend backend() {
            return backend;
        }

        @Override
        public GroupJoinResult join(GroupJoinCommand command) {
            lastCommand = command;
            return new GroupJoinResult("120363joined@g.us", GroupJoinOutcome.JOINED);
        }
    }
}
