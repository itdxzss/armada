package com.armada.marketing.service;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.port.MessageSendPort;
import com.armada.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 营销各发送入口共用的群封禁闸门，在协议 outbox 入队前读取当前群事实。 */
@Service
public class MarketingMessageSendService {
    /** WhatsApp 明确群封禁的稳定原因码，不受异常群偏好开关影响。 */
    public static final String GROUP_BANNED = "GROUP_BANNED";
    private final MarketingTaskMapper mapper;
    private final MessageSendPort port;

    /** 注入当前租户营销查询与真实协议发送端口。 */
    public MarketingMessageSendService(MarketingTaskMapper mapper, MessageSendPort port) {
        this.mapper = mapper;
        this.port = port;
    }

    /** 批量过滤已封禁群并按原命令顺序返回结果；被拦截命令不会写入协议 outbox。 */
    public MessageSendEnqueueResult enqueue(List<MessageSendCommand> commands) {
        if (commands.isEmpty()) {
            return new MessageSendEnqueueResult(List.of());
        }
        Long tenantId = TenantContext.get();
        if (tenantId == null || commands.stream().anyMatch(command ->
                command.correlation() == null || !tenantId.equals(command.correlation().tenantId()))) {
            throw new IllegalArgumentException("营销发送必须使用命令所属租户上下文");
        }
        Set<String> commandIds = new HashSet<>();
        if (commands.stream().anyMatch(command -> !commandIds.add(command.commandId()))) {
            throw new IllegalArgumentException("营销发送命令 ID 重复");
        }
        List<String> groupJids = commands.stream()
                .filter(command -> command.target().kind() == MessageSendCommand.TargetKind.GROUP)
                .map(command -> command.target().jid()).distinct().toList();
        Set<String> banned = groupJids.isEmpty() ? Set.of() : Set.copyOf(mapper.selectBannedGroupJids(groupJids));
        List<MessageSendCommand> allowed = new ArrayList<>();
        Map<String, MessageSendEnqueueItem> results = new HashMap<>();
        for (MessageSendCommand command : commands) {
            if (command.target().kind() == MessageSendCommand.TargetKind.GROUP
                    && banned.contains(command.target().jid())) {
                results.put(command.commandId(), MessageSendEnqueueItem.rejected(
                        command.commandId(), GROUP_BANNED, "群组已封禁，停止发送"));
            } else {
                allowed.add(command);
            }
        }
        if (!allowed.isEmpty()) {
            MessageSendEnqueueResult response = port.enqueue(allowed);
            Set<String> expected = new HashSet<>();
            allowed.forEach(command -> expected.add(command.commandId()));
            if (response == null || response.items() == null || response.items().size() != allowed.size()) {
                throw new IllegalStateException("营销消息入队结果数量与命令不一致");
            }
            for (MessageSendEnqueueItem item : response.items()) {
                if (item == null || !expected.remove(item.commandId())) {
                    throw new IllegalStateException("营销消息入队结果 commandId 与命令不一致");
                }
                results.put(item.commandId(), item);
            }
        }
        return new MessageSendEnqueueResult(commands.stream()
                .map(command -> results.get(command.commandId())).toList());
    }
}
