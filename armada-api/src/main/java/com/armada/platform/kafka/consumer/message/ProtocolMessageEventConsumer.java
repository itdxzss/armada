package com.armada.platform.kafka.consumer.message;

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

@Component
@Profile("kafka")
public class ProtocolMessageEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(ProtocolMessageEventConsumer.class);

    public static final String EVENT_MESSAGE_SEND_RESULT_REPORTED = "message.send_result_reported";

    private final ObjectMapper objectMapper;
    private final ProtocolMessageSendResultReportedSink sink;

    public ProtocolMessageEventConsumer(ObjectMapper objectMapper,
                                        ProtocolMessageSendResultReportedSink sink) {
        this.objectMapper = objectMapper;
        this.sink = sink;
    }

    @KafkaListener(
            topics = "${armada.protocol.kafka.message-events.topic:protocol.message.events.v1}",
            groupId = "${armada.protocol.kafka.message-events.group-id:armada-api-message-events}")
    public void onMessage(String rawMessage) {
        JsonNode envelope = readEnvelope(rawMessage);
        String eventType = text(envelope, "event");
        String eventId = text(envelope, "eventId");
        if (!EVENT_MESSAGE_SEND_RESULT_REPORTED.equals(eventType)) {
            log.warn("协议消息事件暂未接入,跳过 eventId={} eventType={} accountId={} workerId={}",
                    eventId, eventType, text(envelope, "accountId"), text(envelope, "workerId"));
            return;
        }
        ProtocolMessageSendResultReportedEvent event = toSendResultReportedEvent(envelope, dataNode(envelope));
        log.info("协议消息发送结果事件收到 eventId={} tenantId={} taskId={} attemptId={} success={} "
                        + "messageId={} workerId={}",
                event.eventId(), event.tenantId(), event.marketingTaskId(), event.attemptId(), event.success(),
                event.messageId(), event.workerId());
        sink.handleSendResultReported(event);
    }

    private JsonNode readEnvelope(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议消息事件消息为空");
        }
        try {
            return objectMapper.readTree(rawMessage);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议消息事件 JSON 解析失败");
        }
    }

    private static ProtocolMessageSendResultReportedEvent toSendResultReportedEvent(JsonNode envelope, JsonNode data) {
        return new ProtocolMessageSendResultReportedEvent(
                text(envelope, "eventId"),
                requiredLong(data, "tenantId", "协议消息发送结果事件缺少 data.tenantId"),
                requiredLong(data, "marketingTaskId", "协议消息发送结果事件缺少 data.marketingTaskId"),
                requiredLong(data, "targetId", "协议消息发送结果事件缺少 data.targetId"),
                requiredLong(data, "attemptId", "协议消息发送结果事件缺少 data.attemptId"),
                requiredLong(data, "roundNo", "协议消息发送结果事件缺少 data.roundNo"),
                requiredText(data, "protocolAccountId", "协议消息发送结果事件缺少 data.protocolAccountId"),
                requiredText(data, "groupJid", "协议消息发送结果事件缺少 data.groupJid"),
                text(data, "commandId"),
                requiredBoolean(data, "success", "协议消息发送结果事件缺少 data.success"),
                text(data, "messageId"),
                text(data, "reasonCode"),
                text(data, "reasonMessage"),
                timestamp(envelope, data),
                text(envelope, "workerId"));
    }

    private static JsonNode dataNode(JsonNode envelope) {
        return envelope.path("data").isObject() ? envelope.path("data") : envelope;
    }

    private static Long timestamp(JsonNode envelope, JsonNode data) {
        Long value = longValue(data, "timestamp");
        if (value != null) {
            return value;
        }
        String occurredAt = text(envelope, "occurredAt");
        if (occurredAt == null) {
            return null;
        }
        try {
            return Instant.parse(occurredAt).toEpochMilli();
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议消息事件 occurredAt 格式非法");
        }
    }

    private static Long requiredLong(JsonNode node, String fieldName, String errorMessage) {
        Long value = longValue(node, fieldName);
        if (value == null) {
            throw new BusinessException(ErrorCode.VALIDATION, errorMessage);
        }
        return value;
    }

    private static Long longValue(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isLong() || value.isInt()) {
            return value.longValue();
        }
        if (value.isTextual() && !value.asText().isBlank()) {
            try {
                return Long.valueOf(value.asText());
            } catch (NumberFormatException ex) {
                throw new BusinessException(ErrorCode.VALIDATION, "协议消息事件字段不是长整数: " + fieldName);
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "协议消息事件字段不是长整数: " + fieldName);
    }

    private static boolean requiredBoolean(JsonNode node, String fieldName, String errorMessage) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            throw new BusinessException(ErrorCode.VALIDATION, errorMessage);
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isInt() || value.isLong()) {
            return value.intValue() != 0;
        }
        if (value.isTextual() && !value.asText().isBlank()) {
            return Boolean.parseBoolean(value.asText());
        }
        throw new BusinessException(ErrorCode.VALIDATION, "协议消息事件字段不是布尔值: " + fieldName);
    }

    private static String requiredText(JsonNode node, String fieldName, String errorMessage) {
        String value = text(node, fieldName);
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, errorMessage);
        }
        return value;
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText();
    }
}
