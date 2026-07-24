package com.armada.platform.kafka.consumer.pairing;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 协议手机号配对事件消费者。
 *
 * <p>只负责 JSON 校验和模型转换，账号落库及代理流转由业务 Sink 完成。
 * 日志不输出手机号、配对码或凭据。</p>
 */
@Component
@Profile("kafka")
public class ProtocolPairingEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProtocolPairingEventConsumer.class);
    private final ObjectMapper objectMapper;
    private final ProtocolPairingEventSink sink;

    public ProtocolPairingEventConsumer(ObjectMapper objectMapper, ProtocolPairingEventSink sink) {
        this.objectMapper = objectMapper;
        this.sink = sink;
    }

    /** 消费 protocol.pairing.events.v1 的标准 EventEnvelope。 */
    @KafkaListener(
            topics = "${armada.protocol.kafka.pairing-events.topic:protocol.pairing.events.v1}",
            groupId = "${armada.protocol.kafka.pairing-events.group-id:armada-api-pairing-events}")
    public void onMessage(String rawMessage) {
        JsonNode envelope = readEnvelope(rawMessage);
        String eventType = requiredText(envelope, "event", "协议配对事件缺少 event");
        if (!ProtocolPairingEvent.EVENT_CODE_GENERATED.equals(eventType)
                && !ProtocolPairingEvent.EVENT_COMPLETED.equals(eventType)
                && !ProtocolPairingEvent.EVENT_FAILED.equals(eventType)) {
            log.warn("协议配对事件类型暂未接入 eventId={} eventType={}",
                    text(envelope, "eventId"), eventType);
            return;
        }
        ProtocolPairingEvent event = toEvent(envelope, eventType);
        log.info("协议配对事件收到 eventId={} eventType={} workerId={}",
                event.eventId(), event.eventType(), event.workerId());
        sink.handle(event);
    }

    private ProtocolPairingEvent toEvent(JsonNode envelope, String eventType) {
        JsonNode data = envelope.path("data").isObject() ? envelope.path("data") : envelope;
        long occurredAt = requiredInstant(envelope, "occurredAt", "协议配对事件缺少 occurredAt");
        String code = null;
        Long expiresAt = null;
        String phone = null;
        String jid = null;
        String ownerEndpoint = null;
        String reason = null;
        String detectedAccountType = null;
        if (ProtocolPairingEvent.EVENT_CODE_GENERATED.equals(eventType)) {
            code = requiredText(data, "code", "协议配对码事件缺少 data.code");
            expiresAt = requiredInstant(data, "expiresAt", "协议配对码事件缺少 data.expiresAt");
        } else if (ProtocolPairingEvent.EVENT_COMPLETED.equals(eventType)) {
            phone = requiredText(data, "phone", "协议配对完成事件缺少 data.phone");
            jid = text(data, "jid");
            ownerEndpoint = text(data, "ownerEndpoint");
            JsonNode detection = data.path("detection");
            detectedAccountType = detection.isObject() ? text(detection, "accountType") : null;
            Long completedAt = instant(data, "completedAt");
            if (completedAt != null) {
                occurredAt = completedAt;
            }
        } else {
            reason = text(data, "reason");
            Long failedAt = instant(data, "failedAt");
            if (failedAt != null) {
                occurredAt = failedAt;
            }
        }
        return new ProtocolPairingEvent(
                requiredText(envelope, "eventId", "协议配对事件缺少 eventId"),
                eventType,
                requiredText(envelope, "accountId", "协议配对事件缺少 accountId"),
                text(data, "clientRefId"),
                occurredAt,
                text(envelope, "workerId"),
                code,
                expiresAt,
                phone,
                jid,
                ownerEndpoint,
                reason,
                detectedAccountType);
    }

    private JsonNode readEnvelope(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议配对事件消息为空");
        }
        try {
            return objectMapper.readTree(rawMessage);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议配对事件 JSON 解析失败");
        }
    }

    private static long requiredInstant(JsonNode node, String field, String message) {
        Long value = instant(node, field);
        if (value == null) {
            throw new BusinessException(ErrorCode.VALIDATION, message);
        }
        return value;
    }

    private static Long instant(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议配对事件时间格式非法: " + field);
        }
    }

    private static String requiredText(JsonNode node, String field, String message) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, message);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
