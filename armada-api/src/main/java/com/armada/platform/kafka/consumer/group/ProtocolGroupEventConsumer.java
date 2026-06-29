package com.armada.platform.kafka.consumer.group;

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
 * 协议群组事件 Kafka consumer。
 *
 * <p>当前只接入 {@code group.health_reported}。其它群组事件先记录并跳过,
 * 防止未定义回写口径的事件阻塞消费。</p>
 */
@Component
@Profile("kafka")
public class ProtocolGroupEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProtocolGroupEventConsumer.class);

    /** 协议层群链接健康检测回报事件类型。 */
    public static final String EVENT_GROUP_HEALTH_REPORTED = "group.health_reported";

    private final ObjectMapper objectMapper;
    private final ProtocolGroupHealthReportedSink healthReportedSink;

    /**
     * 创建协议群组事件 consumer。
     *
     * @param objectMapper       JSON 解析器
     * @param healthReportedSink 群组健康检测下游处理口
     */
    public ProtocolGroupEventConsumer(ObjectMapper objectMapper,
                                      ProtocolGroupHealthReportedSink healthReportedSink) {
        this.objectMapper = objectMapper;
        this.healthReportedSink = healthReportedSink;
    }

    /**
     * 消费协议群组事件消息。
     *
     * <p>Kafka value 是协议层 {@code EventEnvelope} JSON。方法保持 public 便于单测直接覆盖解析逻辑。</p>
     *
     * @param rawMessage Kafka message value
     */
    @KafkaListener(
            topics = "${armada.protocol.kafka.group-events.topic:protocol.group.events.v1}",
            groupId = "${armada.protocol.kafka.group-events.group-id:armada-api-group-events}")
    public void onMessage(String rawMessage) {
        JsonNode envelope = readEnvelope(rawMessage);
        String eventType = text(envelope, "event");
        String eventId = text(envelope, "eventId");
        if (!EVENT_GROUP_HEALTH_REPORTED.equals(eventType)) {
            log.warn("协议群组事件暂未接入,跳过 eventId={} eventType={} accountId={} workerId={}",
                    eventId, eventType, text(envelope, "accountId"), text(envelope, "workerId"));
            return;
        }

        JsonNode data = dataNode(envelope);
        Long tenantId = longValue(data, "tenantId");
        Long groupLinkId = longValue(data, "groupLinkId");
        if (tenantId == null || groupLinkId == null) {
            log.warn("协议群组健康事件缺少租户或链接主键,跳过 eventId={} tenantId={} groupLinkId={} groupJid={}",
                    eventId, tenantId, groupLinkId, text(data, "groupJid"));
            return;
        }

        ProtocolGroupHealthReportedEvent event = toHealthReportedEvent(envelope, data, tenantId, groupLinkId);
        log.info("协议群组健康事件收到 eventId={} tenantId={} groupLinkId={} groupJid={} health={} "
                        + "memberCount={} workerId={}",
                event.eventId(), event.tenantId(), event.groupLinkId(), event.groupJid(), event.health(),
                event.memberCount(), event.workerId());
        healthReportedSink.handleHealthReported(event);
    }

    private JsonNode readEnvelope(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群组事件消息为空");
        }
        try {
            return objectMapper.readTree(rawMessage);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群组事件 JSON 解析失败");
        }
    }

    private static ProtocolGroupHealthReportedEvent toHealthReportedEvent(JsonNode envelope,
                                                                          JsonNode data,
                                                                          Long tenantId,
                                                                          Long groupLinkId) {
        return new ProtocolGroupHealthReportedEvent(
                text(envelope, "eventId"),
                tenantId,
                groupLinkId,
                requiredText(data, "groupJid", "协议群组健康事件缺少 data.groupJid"),
                requiredText(data, "health", "协议群组健康事件缺少 data.health"),
                integer(data, "memberCount"),
                checkedAt(envelope, data),
                text(data, "errorCode"),
                text(data, "subject"),
                text(envelope, "accountId"),
                text(envelope, "workerId"));
    }

    private static JsonNode dataNode(JsonNode envelope) {
        return envelope.path("data").isObject() ? envelope.path("data") : envelope;
    }

    private static Long checkedAt(JsonNode envelope, JsonNode data) {
        String value = text(data, "checkedAt");
        if (value == null) {
            value = text(envelope, "occurredAt");
        }
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群组事件 checkedAt 格式非法");
        }
    }

    private static Integer integer(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isInt() || value.isLong()) {
            return value.intValue();
        }
        if (value.isTextual() && !value.asText().isBlank()) {
            try {
                return Integer.valueOf(value.asText());
            } catch (NumberFormatException ex) {
                throw new BusinessException(ErrorCode.VALIDATION, "协议群组事件字段不是整数: " + fieldName);
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "协议群组事件字段不是整数: " + fieldName);
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
                throw new BusinessException(ErrorCode.VALIDATION, "协议群组事件字段不是长整数: " + fieldName);
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "协议群组事件字段不是长整数: " + fieldName);
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
