package com.armada.task.service.impl;

import com.armada.platform.protocol.model.command.ProtocolJoinTaskAdminCommandRequest;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.service.ProtocolCommandPayloadHydrator;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.JoinTaskAdminMapper;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.service.JoinTaskAdminFacts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 提权命令发送前重查有效任务与当前尝试，防止软删、终结或旧重试继续发送。 */
@Component
public class JoinTaskAdminPayloadHydrator implements ProtocolCommandPayloadHydrator {
    private final JoinTaskAdminMapper admins;
    private final JoinTaskMapper tasks;
    private final JoinTaskAdminFacts facts;
    private final ObjectMapper json;

    /** 装配只读任务事实与协议身份补全。 */
    public JoinTaskAdminPayloadHydrator(JoinTaskAdminMapper admins, JoinTaskMapper tasks,
            JoinTaskAdminFacts facts, ObjectMapper json) {
        this.admins = admins; this.tasks = tasks; this.facts = facts; this.json = json;
    }
    /** 只接管独立进群任务管理员命令。 */
    @Override
    public boolean supports(ProtocolCommandOutbox command) {
        return ProtocolJoinTaskAdminCommandRequest.AGGREGATE.equals(command.getAggregateType());
    }
    /** 基于当前尝试构造双协议一致的消息，敏感执行字段不回写 Outbox。 */
    @Override
    public JsonNode hydrate(ProtocolCommandOutbox command, JsonNode reference) {
        Long previous = TenantContext.get();
        TenantContext.set(command.getTenantId());
        try {
            var row = admins.find(command.getAggregateId());
            if (row == null || row.getAdminStatus() != 2
                    || !Objects.equals(row.getAdminCommandId(), command.getCommandId())
                    || reference.path("joinTaskResultId").asLong() != row.getId()
                    || reference.path("joinTaskId").asLong() != row.getJoinTaskId()) {
                throw new IllegalStateException("进群管理员命令已失效");
            }
            var task = tasks.selectByTenantAndId(row.getJoinTaskId());
            if (task == null || !task.isSetAdminEnabled() || !"RUNNING".equals(task.getStatus())) {
                throw new IllegalStateException("进群任务已结束或删除");
            }
            var actor = facts.actor(row.getAdminActorAccountId()).orElseThrow(() -> new IllegalStateException("原有管理员不可执行"));
            if (!actor.protocolAccountId().equals(command.getProtocolAccountId())
                    || !actor.backend().name().equals(command.getProtocolBackend())) throw new IllegalStateException("管理员协议身份已改变");
            String target = facts.targetJid(row);
            if (target.isBlank()) throw new IllegalStateException("进群目标账号身份缺失");
            var payload = json.createObjectNode();
            payload.put("tenantId", command.getTenantId());
            payload.put("joinTaskId", row.getJoinTaskId());
            payload.put("joinTaskResultId", row.getId());
            payload.put("source", ProtocolJoinTaskAdminCommandRequest.SOURCE);
            payload.put("accountId", actor.armadaAccountId());
            payload.put("protocolAccountId", actor.protocolAccountId());
            payload.put("protocolBackend", actor.backend().name());
            payload.put("wsPhone", actor.wsPhone());
            payload.put("groupJid", row.getGroupJid());
            payload.putArray("participants").add(target);
            payload.put("action", "PROMOTE");
            payload.put("attemptNo", row.getAdminAttemptNo());
            payload.put("timeoutMs", 30_000);
            return payload;
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
        }
    }
}
