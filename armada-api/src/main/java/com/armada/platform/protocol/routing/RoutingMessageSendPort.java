package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.port.MessageSendPort;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 根据账号协议后端批量分发消息命令的统一端口。
 */
public final class RoutingMessageSendPort implements MessageSendPort {

    private static final String UNSUPPORTED_BACKEND = "UNSUPPORTED_BACKEND";

    private final Map<ProtocolBackend, MessageSendBackend> backends;

    public RoutingMessageSendPort(List<MessageSendBackend> implementations) {
        EnumMap<ProtocolBackend, MessageSendBackend> resolved = new EnumMap<>(ProtocolBackend.class);
        if (implementations != null) {
            for (MessageSendBackend implementation : implementations) {
                if (implementation == null || implementation.backend() == null) {
                    continue;
                }
                MessageSendBackend previous = resolved.putIfAbsent(implementation.backend(), implementation);
                if (previous != null) {
                    throw new IllegalStateException(
                            "重复的消息发送协议后端 backend=" + implementation.backend());
                }
            }
        }
        this.backends = Map.copyOf(resolved);
    }

    @Override
    public MessageSendEnqueueResult enqueue(List<MessageSendCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return new MessageSendEnqueueResult(List.of());
        }
        EnumMap<ProtocolBackend, List<MessageSendCommand>> grouped = groupCommands(commands);
        Map<String, MessageSendEnqueueItem> results = new HashMap<>();
        for (Map.Entry<ProtocolBackend, List<MessageSendCommand>> entry : grouped.entrySet()) {
            MessageSendBackend implementation = backends.get(entry.getKey());
            if (implementation == null) {
                for (MessageSendCommand command : entry.getValue()) {
                    results.put(command.commandId(), MessageSendEnqueueItem.rejected(
                            command.commandId(),
                            UNSUPPORTED_BACKEND,
                            "消息发送协议后端未注册 backend=" + entry.getKey()));
                }
                continue;
            }
            mergeBackendResults(entry.getValue(), implementation.enqueue(entry.getValue()), results);
        }
        List<MessageSendEnqueueItem> ordered = commands.stream()
                .map(command -> results.get(command.commandId()))
                .toList();
        return new MessageSendEnqueueResult(ordered);
    }

    private static EnumMap<ProtocolBackend, List<MessageSendCommand>> groupCommands(
            List<MessageSendCommand> commands) {
        EnumMap<ProtocolBackend, List<MessageSendCommand>> grouped = new EnumMap<>(ProtocolBackend.class);
        Set<String> commandIds = new HashSet<>();
        for (MessageSendCommand command : commands) {
            if (command == null || command.account() == null || command.account().backend() == null
                    || command.commandId() == null || command.commandId().isBlank()) {
                throw new IllegalArgumentException("消息发送命令缺少路由字段");
            }
            if (!commandIds.add(command.commandId())) {
                throw new IllegalArgumentException("消息发送命令 ID 重复 commandId=" + command.commandId());
            }
            grouped.computeIfAbsent(command.account().backend(), ignored -> new ArrayList<>()).add(command);
        }
        return grouped;
    }

    private static void mergeBackendResults(
            List<MessageSendCommand> commands,
            MessageSendEnqueueResult result,
            Map<String, MessageSendEnqueueItem> merged) {
        Set<String> expected = commands.stream()
                .map(MessageSendCommand::commandId)
                .collect(java.util.stream.Collectors.toSet());
        if (result == null || result.items() == null || result.items().size() != expected.size()) {
            throw backendResultMismatch();
        }
        Set<String> returned = new HashSet<>();
        for (MessageSendEnqueueItem item : result.items()) {
            if (item == null || !expected.contains(item.commandId()) || !returned.add(item.commandId())) {
                throw backendResultMismatch();
            }
            merged.put(item.commandId(), item);
        }
        if (!returned.equals(expected)) {
            throw backendResultMismatch();
        }
    }

    private static IllegalStateException backendResultMismatch() {
        return new IllegalStateException("消息发送后端返回结果与输入命令不一致");
    }
}
