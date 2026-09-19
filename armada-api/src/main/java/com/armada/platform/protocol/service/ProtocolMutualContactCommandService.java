package com.armada.platform.protocol.service;
import com.armada.platform.kafka.config.ProtocolAndroidCommandProperties;
import com.armada.platform.kafka.config.ProtocolMasterCommandProperties;
import com.armada.platform.kafka.dispatch.ProtocolCommandDispatchTrigger;
import com.armada.platform.protocol.mapper.ProtocolCommandOutboxMapper;
import com.armada.platform.protocol.model.command.ProtocolMutualContactCommandRequest;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.enums.ProtocolCommandOutboxStatus;
import com.armada.shared.tenant.TenantContext;
import com.armada.shared.trace.TraceIds;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/** 使用现有 Outbox 和 afterCommit 派发，独立文件避免修改其他任务的入队规则。 */
@Service
public class ProtocolMutualContactCommandService {
    private final ProtocolCommandOutboxMapper mapper;
    private final ProtocolCommandDispatchTrigger trigger;
    private final ProtocolMasterCommandProperties web;
    private final ProtocolAndroidCommandProperties android;
    public ProtocolMutualContactCommandService(ProtocolCommandOutboxMapper mapper,
            ProtocolCommandDispatchTrigger trigger, ProtocolMasterCommandProperties web,
            ProtocolAndroidCommandProperties android) {
        this.mapper = mapper;
        this.trigger = trigger;
        this.web = web;
        this.android = android;
    }
    /** 与业务方向状态同事务提交，不能在业务回滚时发送。 */
    @Transactional(rollbackFor = Exception.class)
    public String enqueue(ProtocolMutualContactCommandRequest req) {
        if (req == null || !Objects.equals(TenantContext.get(), req.tenantId()) || req.taskId() == null
                || req.taskId() <= 0 || req.itemId() == null || req.itemId() <= 0 || req.actor() == null)
            throw new IllegalArgumentException("invalid mutual contact command");
        long now = System.currentTimeMillis();
        var row = new ProtocolCommandOutbox();
        row.setTenantId(req.tenantId());
        row.setCommandId(UUID.randomUUID().toString());
        row.setBatchId("mutual-contact:" + req.taskId());
        row.setCommandType("contact.save.requested");
        row.setAggregateType(ProtocolMutualContactCommandRequest.AGGREGATE);
        row.setAggregateId(req.itemId());
        row.setProtocolBackend(req.actor().backend().name());
        row.setProtocolAccountId(req.actor().protocolAccountId());
        row.setKafkaKey(req.actor().protocolAccountId());
        row.setKafkaTopic(
                req.actor().backend() == ProtocolBackend.ANDROID ? android.getGroupActionTopic() : web.getTopic());
        row.setPayloadJson("{\"tenantId\":" + req.tenantId() + ",\"taskId\":" + req.taskId() + ",\"itemId\":"
                + req.itemId() + ",\"source\":\"" + ProtocolMutualContactCommandRequest.SOURCE + "\"}");
        row.setStatus(ProtocolCommandOutboxStatus.PENDING.code());
        row.setRetryCount(0);
        row.setNextRetryAt(0L);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setTraceId(TraceIds.newTraceId());
        if (mapper.batchInsertPending(List.of(row)) != 1)
            throw new IllegalStateException("mutual contact outbox insert failed");
        trigger.dispatchAfterCommit(List.of(row));
        return row.getCommandId();
    }
}
