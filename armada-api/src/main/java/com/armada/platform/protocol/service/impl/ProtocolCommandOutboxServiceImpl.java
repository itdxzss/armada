package com.armada.platform.protocol.service.impl;

import com.armada.platform.protocol.mapper.ProtocolCommandOutboxMapper;
import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.protocol.model.command.ProtocolOnlineCommandRequest;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.enums.ProtocolCommandOutboxStatus;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandDispatchTrigger;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 协议命令 Outbox 应用服务实现。
 *
 * <p>本服务只在本地事务中写 outbox。事务提交后通过 trigger 异步唤醒 dispatcher,
 * Kafka 发送不包在本事务内。</p>
 */
@Service
public class ProtocolCommandOutboxServiceImpl implements ProtocolCommandOutboxService {

    private static final Logger log = LoggerFactory.getLogger(ProtocolCommandOutboxServiceImpl.class);

    /** 账号上线命令类型。 */
    public static final String COMMAND_TYPE_ACCOUNT_ONLINE_REQUESTED = "account.online.requested";

    /** 账号聚合类型。 */
    public static final String AGGREGATE_TYPE_ACCOUNT = "ACCOUNT";

    /** 账号协议命令 topic。 */
    public static final String TOPIC_PROTOCOL_ACCOUNT_COMMANDS = "protocol.account.commands.v1";

    private static final int MAX_ONLINE_COMMANDS = 500;
    private static final long IMMEDIATE_RETRY_AT = 0L;
    private static final String COMMAND_ID_PREFIX = "cmd_";
    private static final String BATCH_ID_PREFIX = "batch_";

    private final ProtocolCommandOutboxMapper mapper;
    private final ObjectMapper objectMapper;
    private final ProtocolCommandDispatchTrigger dispatchTrigger;

    /**
     * 创建协议命令 Outbox service。
     *
     * @param mapper          outbox mapper
     * @param objectMapper    JSON 序列化器
     * @param dispatchTrigger outbox 提交后 dispatch 触发器
     */
    public ProtocolCommandOutboxServiceImpl(ProtocolCommandOutboxMapper mapper,
                                            ObjectMapper objectMapper,
                                            ProtocolCommandDispatchTrigger dispatchTrigger) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.dispatchTrigger = dispatchTrigger;
    }

    /**
     * 批量写入账号上线 outbox 命令。
     *
     * <p>单条命令不生成 batch_id;多条命令共享一个 batch_id,便于批量上线排查。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProtocolCommandOutboxEnqueueResult enqueueOnlineCommands(List<ProtocolOnlineCommandRequest> commands) {
        validateCommands(commands);

        String batchId = commands.size() == 1 ? null : newBatchId();
        long now = System.currentTimeMillis();
        List<String> commandIds = new ArrayList<>(commands.size());
        List<ProtocolCommandOutbox> rows = new ArrayList<>(commands.size());
        Set<String> uniqueCommandIds = new HashSet<>(commands.size());

        for (ProtocolOnlineCommandRequest command : commands) {
            String commandId = newCommandId();
            if (!uniqueCommandIds.add(commandId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "协议命令 ID 重复: " + commandId);
            }
            commandIds.add(commandId);
            rows.add(toOutboxRow(command, commandId, batchId, now));
        }

        int inserted;
        try {
            inserted = mapper.batchInsertPending(rows);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "协议命令 ID 已存在");
        }
        // outbox 已写入当前事务,真正 Kafka 发送必须等 commit 后再触发,避免发送已回滚命令。
        dispatchTrigger.dispatchAfterCommit(rows);
        log.info("协议命令 outbox 已写入 batchId={} commandCount={} inserted={}",
                batchId, commandIds.size(), inserted);
        return new ProtocolCommandOutboxEnqueueResult(batchId, commandIds, inserted);
    }

    /**
     * 生成 outbox command_id。
     *
     * @return 全局唯一 command_id
     */
    protected String newCommandId() {
        return COMMAND_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 生成 outbox batch_id。
     *
     * @return 批量命令归组 ID
     */
    protected String newBatchId() {
        return BATCH_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    private ProtocolCommandOutbox toOutboxRow(ProtocolOnlineCommandRequest command,
                                              String commandId,
                                              String batchId,
                                              long now) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setCommandId(commandId);
        row.setBatchId(batchId);
        row.setCommandType(COMMAND_TYPE_ACCOUNT_ONLINE_REQUESTED);
        row.setAggregateType(AGGREGATE_TYPE_ACCOUNT);
        row.setAggregateId(command.accountId());
        row.setKafkaTopic(TOPIC_PROTOCOL_ACCOUNT_COMMANDS);
        row.setKafkaKey(command.protocolAccountId());
        row.setProtocolAccountId(command.protocolAccountId());
        row.setPayloadJson(payloadJson(command));
        row.setStatus(ProtocolCommandOutboxStatus.PENDING.code());
        row.setRetryCount(0);
        row.setNextRetryAt(IMMEDIATE_RETRY_AT);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private String payloadJson(ProtocolOnlineCommandRequest command) {
        ProtocolOnlineCommandPayload payload = new ProtocolOnlineCommandPayload(
                command.accountId(),
                command.protocolAccountId(),
                command.credentialFormat(),
                command.proxyId(),
                command.source());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议命令 payload 序列化失败");
        }
    }

    private void validateCommands(List<ProtocolOnlineCommandRequest> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议上线命令不能为空");
        }
        if (commands.size() > MAX_ONLINE_COMMANDS) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议上线命令不能超过 500 条");
        }
        for (ProtocolOnlineCommandRequest command : commands) {
            validateCommand(command);
        }
    }

    private void validateCommand(ProtocolOnlineCommandRequest command) {
        if (command == null
                || command.accountId() == null
                || isBlank(command.protocolAccountId())
                || command.credentialFormat() == null
                || command.proxyId() == null
                || isBlank(command.source())) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议上线命令缺少必要字段");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ProtocolOnlineCommandPayload(
            Long accountId,
            String protocolAccountId,
            CredentialFormat credentialFormat,
            Long proxyId,
            String source
    ) {
    }
}
