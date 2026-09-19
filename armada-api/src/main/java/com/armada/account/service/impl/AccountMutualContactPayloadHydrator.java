package com.armada.account.service.impl;
import com.armada.account.mapper.AccountMutualContactMapper;
import com.armada.account.model.enums.AccountMutualContactStatus;
import com.armada.platform.protocol.model.command.ProtocolMutualContactCommandRequest;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.service.ProtocolCommandPayloadHydrator;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.stereotype.Component;
/** 只从冻结方向明细补全可执行 payload，禁止从前端取得协议路由。 */
@Component
public class AccountMutualContactPayloadHydrator implements ProtocolCommandPayloadHydrator {
    private final AccountMutualContactMapper mapper;
    private final ObjectMapper json;
    public AccountMutualContactPayloadHydrator(AccountMutualContactMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }
    @Override
    public boolean supports(ProtocolCommandOutbox row) {
        return row != null && ProtocolMutualContactCommandRequest.AGGREGATE.equals(row.getAggregateType())
                && "contact.save.requested".equals(row.getCommandType());
    }
    @Override
    public JsonNode hydrate(ProtocolCommandOutbox row, JsonNode ref) {
        Long previous = TenantContext.get();
        TenantContext.set(row.getTenantId());
        try {
            var i = mapper.item(row.getAggregateId());
            if (i == null || !Objects.equals(i.getCommandId(), row.getCommandId())
                    || !Objects.equals(i.getProtocolAccountId(), row.getProtocolAccountId())
                    || !Objects.equals(i.getProtocolBackend(), row.getProtocolBackend())
                    || ref.path("tenantId").asLong() != row.getTenantId()
                    || ref.path("taskId").asLong() != i.getTaskId() || ref.path("itemId").asLong() != i.getId()
                    || !ProtocolMutualContactCommandRequest.SOURCE.equals(ref.path("source").asText())
                    || (i.getStatus() != AccountMutualContactStatus.SUBMITTED.code()
                            && i.getStatus() != AccountMutualContactStatus.UNKNOWN.code()))
                throw new IllegalStateException("mutual contact reference mismatch");
            var payload = json.createObjectNode();
            payload.put("tenantId", i.getTenantId());
            payload.put("taskId", i.getTaskId());
            payload.put("itemId", i.getId());
            payload.put("accountId", i.getActorId());
            payload.put("protocolAccountId", i.getProtocolAccountId());
            payload.put("protocolBackend", i.getProtocolBackend());
            payload.put("wsPhone", i.getActorPhone());
            payload.put("contact", i.getTargetPhone());
            payload.put("name", i.getTargetPhone());
            payload.put("attemptNo", i.getAttemptNo());
            payload.put("source", ProtocolMutualContactCommandRequest.SOURCE);
            return payload;
        } finally {
            if (previous == null)
                TenantContext.clear();
            else
                TenantContext.set(previous);
        }
    }
}
