package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.MessageType;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingMessageSendPortTest {

    @Test
    void routesMixedCommandsAndPreservesInputOrder() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB, Set.of());
        RecordingBackend android = new RecordingBackend(
                ProtocolBackend.ANDROID,
                Set.of("cmd_android_rejected"));
        RoutingMessageSendPort port = new RoutingMessageSendPort(List.of(web, android));

        MessageSendEnqueueResult result = port.enqueue(List.of(
                command("cmd_web", ProtocolBackend.WEB),
                command("cmd_android_rejected", ProtocolBackend.ANDROID),
                command("cmd_android", ProtocolBackend.ANDROID)));

        assertThat(web.commandIds()).containsExactly("cmd_web");
        assertThat(android.commandIds()).containsExactly("cmd_android_rejected", "cmd_android");
        assertThat(result.items()).extracting(MessageSendEnqueueItem::commandId)
                .containsExactly("cmd_web", "cmd_android_rejected", "cmd_android");
        assertThat(result.items()).extracting(MessageSendEnqueueItem::accepted)
                .containsExactly(true, false, true);
    }

    @Test
    void rejectsCommandsWhoseBackendIsNotRegistered() {
        RoutingMessageSendPort port = new RoutingMessageSendPort(List.of(
                new RecordingBackend(ProtocolBackend.WEB, Set.of())));

        MessageSendEnqueueResult result = port.enqueue(List.of(
                command("cmd_android", ProtocolBackend.ANDROID)));

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.commandId()).isEqualTo("cmd_android");
            assertThat(item.accepted()).isFalse();
            assertThat(item.reasonCode()).isEqualTo("UNSUPPORTED_BACKEND");
        });
    }

    @Test
    void rejectsDuplicateBackendRegistration() {
        assertThatThrownBy(() -> new RoutingMessageSendPort(List.of(
                new RecordingBackend(ProtocolBackend.WEB, Set.of()),
                new RecordingBackend(ProtocolBackend.WEB, Set.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复的消息发送协议后端")
                .hasMessageContaining("WEB");
    }

    @Test
    void rejectsIncompleteOrUnknownBackendResults() {
        MessageSendBackend incomplete = new MessageSendBackend() {
            @Override
            public ProtocolBackend backend() {
                return ProtocolBackend.WEB;
            }

            @Override
            public MessageSendEnqueueResult enqueue(List<MessageSendCommand> commands) {
                return new MessageSendEnqueueResult(List.of(
                        MessageSendEnqueueItem.accepted("unexpected")));
            }
        };
        RoutingMessageSendPort port = new RoutingMessageSendPort(List.of(incomplete));

        assertThatThrownBy(() -> port.enqueue(List.of(command("cmd_web", ProtocolBackend.WEB))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("返回结果与输入命令不一致");
    }

    private static MessageSendCommand command(String commandId, ProtocolBackend backend) {
        return new MessageSendCommand(
                new ProtocolAccountRef(10L, backend, "acc_919000000001", "919000000001"),
                new MessageSendCommand.MessageTarget("120363001@g.us"),
                new MessageSendCommand.MessagePayload(
                        MessageType.TEXT,
                        new MessageSendCommand.MessageContent("hello", null, null, null),
                        false),
                new MessageSendCommand.MessageCorrelation(
                        1L,
                        "marketing_task",
                        new MessageSendCommand.MarketingCorrelation(2L, 3L, 4L, 1L),
                        null),
                commandId);
    }

    private static final class RecordingBackend implements MessageSendBackend {
        private final ProtocolBackend backend;
        private final Set<String> rejectedCommandIds;
        private final List<String> commandIds = new ArrayList<>();

        private RecordingBackend(ProtocolBackend backend, Set<String> rejectedCommandIds) {
            this.backend = backend;
            this.rejectedCommandIds = new HashSet<>(rejectedCommandIds);
        }

        @Override
        public ProtocolBackend backend() {
            return backend;
        }

        @Override
        public MessageSendEnqueueResult enqueue(List<MessageSendCommand> commands) {
            List<MessageSendEnqueueItem> items = commands.stream()
                    .map(command -> {
                        commandIds.add(command.commandId());
                        if (rejectedCommandIds.contains(command.commandId())) {
                            return MessageSendEnqueueItem.rejected(
                                    command.commandId(),
                                    "REJECTED_FOR_TEST",
                                    "测试拒绝");
                        }
                        return MessageSendEnqueueItem.accepted(command.commandId());
                    })
                    .toList();
            return new MessageSendEnqueueResult(items);
        }

        private List<String> commandIds() {
            return List.copyOf(commandIds);
        }
    }
}
