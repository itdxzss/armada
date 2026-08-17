package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupMemberListQuery;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingGroupMemberListPortTest {

    @Test
    void routesOnlyToTheBackendSelectedByTheAccountReference() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        RecordingBackend android = new RecordingBackend(ProtocolBackend.ANDROID);
        RoutingGroupMemberListPort port = new RoutingGroupMemberListPort(List.of(web, android));
        GroupMemberListQuery query = query(ProtocolBackend.ANDROID);

        List<GroupParticipantResult> result = port.list(query);

        assertThat(result).containsExactly(new GroupParticipantResult(
"919000000002@s.whatsapp.net", null, "919000000002", false, false, null));
        assertThat(web.lastQuery).isNull();
        assertThat(android.lastQuery).isSameAs(query);
    }

    @Test
    void rejectsDuplicateAndMissingBackendRegistrations() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        assertThatThrownBy(() -> new RoutingGroupMemberListPort(List.of(web, web)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEB");

        RoutingGroupMemberListPort port = new RoutingGroupMemberListPort(List.of(web));
        assertThatThrownBy(() -> port.list(query(ProtocolBackend.ANDROID)))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.UNSUPPORTED_BACKEND);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("group.members.list");
                    assertThat(ex.operationId()).contains("group-creation-marketing-item:11");
                });
    }

    private static GroupMemberListQuery query(ProtocolBackend backend) {
        return new GroupMemberListQuery(
                new ProtocolAccountRef(7L, backend, "acc_7", "919000000001"),
                "120363created@g.us",
                "group-creation-marketing-item:11");
    }

    private static final class RecordingBackend implements GroupMemberListBackend {
        private final ProtocolBackend backend;
        private GroupMemberListQuery lastQuery;

        private RecordingBackend(ProtocolBackend backend) {
            this.backend = backend;
        }

        @Override
        public ProtocolBackend backend() {
            return backend;
        }

        @Override
        public List<GroupParticipantResult> list(GroupMemberListQuery query) {
            lastQuery = query;
            return List.of(new GroupParticipantResult(
"919000000002@s.whatsapp.net", null, "919000000002", false, false, null));
        }
    }
}
