package com.armada.task.service;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.JoinTaskAdminMapper;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.entity.JoinTaskResult;
import com.armada.task.service.impl.JoinTaskAdminPayloadHydrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 发布前验证当前任务/尝试，命令使用原有管理员协议身份。 */
class JoinTaskAdminPayloadHydratorTest {
    private final JoinTaskAdminMapper admins = mock(JoinTaskAdminMapper.class);
    private final JoinTaskMapper tasks = mock(JoinTaskMapper.class);
    private final JoinTaskAdminFacts facts = mock(JoinTaskAdminFacts.class);
    private final ObjectMapper json = new ObjectMapper();
    private final JoinTaskAdminPayloadHydrator hydrator = new JoinTaskAdminPayloadHydrator(admins, tasks, facts, json);
    private ProtocolCommandOutbox command;
    private JoinTaskResult row;
    private ObjectNode reference;

    @BeforeEach
    void setup() {
        TenantContext.set(99L);
        command = new ProtocolCommandOutbox(); command.setTenantId(7L); command.setAggregateType("JOIN_TASK_ADMIN");
        command.setAggregateId(20L); command.setCommandId("cmd-2"); command.setProtocolBackend("ANDROID");
        command.setProtocolAccountId("actor");
        row = new JoinTaskResult(); row.setId(20L); row.setJoinTaskId(10L); row.setAdminStatus(2);
        row.setAdminCommandId("cmd-2"); row.setAdminAttemptNo(2); row.setAdminActorAccountId(40L);
        row.setGroupJid("123@g.us");
        var task = new JoinTask(); task.setSetAdminEnabled(true); task.setStatus("RUNNING");
        when(admins.find(20L)).thenReturn(row); when(tasks.selectByTenantAndId(10L)).thenReturn(task);
        when(facts.actor(40L)).thenReturn(Optional.of(new ProtocolAccountRef(40L, ProtocolBackend.ANDROID, "actor", "67890")));
        when(facts.targetJid(row)).thenReturn("12345@s.whatsapp.net");
        reference = json.createObjectNode().put("joinTaskId", 10L).put("joinTaskResultId", 20L);
    }

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void payloadUsesActorIdentityAndExactTargetWithoutPullTaskCorrelation() {
        var payload = hydrator.hydrate(command, reference);
        assertEquals("ANDROID", payload.path("protocolBackend").asText());
        assertEquals(40L, payload.path("accountId").asLong());
        assertEquals("12345@s.whatsapp.net", payload.path("participants").get(0).asText());
        assertEquals(2, payload.path("attemptNo").asInt());
        assertEquals("join_task_admin", payload.path("source").asText());
        assertFalse(payload.has("pullTaskId"));
        assertEquals(99L, TenantContext.get());
    }

    @Test
    void staleCommandCannotPublishAndRestoresTenant() {
        row.setAdminCommandId("cmd-3");
        assertThrows(IllegalStateException.class, () -> hydrator.hydrate(command, reference));
        verifyNoInteractions(facts);
        assertEquals(99L, TenantContext.get());
    }

    @Test
    void deletedTaskOrChangedProtocolIdentityCannotPublish() {
        command.setProtocolAccountId("old-actor");
        assertThrows(IllegalStateException.class, () -> hydrator.hydrate(command, reference));
        when(tasks.selectByTenantAndId(10L)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> hydrator.hydrate(command, reference));
        assertEquals(99L, TenantContext.get());
    }
}
