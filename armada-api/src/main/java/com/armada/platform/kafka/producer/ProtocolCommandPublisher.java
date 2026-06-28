package com.armada.platform.kafka.producer;

import com.armada.platform.kafka.config.ProtocolCommandPublisherProperties;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolCommandEnvelope;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.result.ProtocolCommandPublishResult;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * 协议命令 Kafka publisher。
 *
 * <p>本类只负责把已存在的 outbox row 转换为 Kafka envelope 并发送。它不扫描 outbox,
 * 不修改 outbox 状态,也不接入账号上线入口。</p>
 */
@Service
public class ProtocolCommandPublisher {

    private static final Logger log = LoggerFactory.getLogger(ProtocolCommandPublisher.class);

    private final KafkaTemplate<String, ProtocolCommandEnvelope> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ProtocolCommandPublisherProperties properties;

    /**
     * 创建协议命令 Kafka publisher。
     *
     * @param kafkaTemplate KafkaTemplate
     * @param objectMapper JSON 解析器
     * @param properties publisher 配置
     */
    public ProtocolCommandPublisher(KafkaTemplate<String, ProtocolCommandEnvelope> kafkaTemplate,
                                    ObjectMapper objectMapper,
                                    ProtocolCommandPublisherProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 发送单条协议命令。
     *
     * @param row 待发送 outbox 行
     * @return 发送结果,供后续 scheduler 标记 SENT 使用
     * @throws BusinessException outbox 行缺少必要字段或 payload JSON 非法时抛出
     * @throws ProtocolException Kafka send 失败或超时时抛出,供后续 scheduler 标记 retry/dead
     */
    public ProtocolCommandPublishResult publish(ProtocolCommandOutbox row) {
        validateRow(row);
        ProtocolCommandEnvelope envelope = toEnvelope(row);
        try {
            kafkaTemplate.send(row.getKafkaTopic(), row.getKafkaKey(), envelope)
                    .get(properties.getSendTimeoutMs(), TimeUnit.MILLISECONDS);
            log.debug("协议命令 Kafka 发送成功 commandId={} batchId={} accountId={} protocolAccountId={} topic={}",
                    row.getCommandId(), row.getBatchId(), row.getAggregateId(), row.getProtocolAccountId(),
                    row.getKafkaTopic());
            return new ProtocolCommandPublishResult(row.getCommandId(), row.getKafkaTopic(), row.getKafkaKey());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw kafkaFailure(row, ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw kafkaFailure(row, ex);
        }
    }

    private ProtocolCommandEnvelope toEnvelope(ProtocolCommandOutbox row) {
        return new ProtocolCommandEnvelope(
                row.getCommandId(),
                row.getBatchId(),
                row.getCommandType(),
                row.getAggregateType(),
                row.getAggregateId(),
                row.getProtocolAccountId(),
                payload(row));
    }

    private JsonNode payload(ProtocolCommandOutbox row) {
        try {
            return objectMapper.readTree(row.getPayloadJson());
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "协议命令 payload JSON 非法: " + row.getCommandId());
        }
    }

    private void validateRow(ProtocolCommandOutbox row) {
        if (row == null
                || isBlank(row.getCommandId())
                || isBlank(row.getCommandType())
                || isBlank(row.getAggregateType())
                || row.getAggregateId() == null
                || isBlank(row.getKafkaTopic())
                || isBlank(row.getKafkaKey())
                || isBlank(row.getProtocolAccountId())
                || isBlank(row.getPayloadJson())) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议命令 outbox 行缺少必要字段");
        }
    }

    private ProtocolException kafkaFailure(ProtocolCommandOutbox row, Exception ex) {
        return ProtocolException.unknown("协议命令 Kafka 发送失败 commandId=" + row.getCommandId(), ex);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
