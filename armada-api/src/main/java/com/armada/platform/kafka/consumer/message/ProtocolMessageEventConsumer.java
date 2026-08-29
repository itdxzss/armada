package com.armada.platform.kafka.consumer.message;

import com.armada.platform.kafka.trace.KafkaTraceSupport;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.trace.TraceContext;
import com.armada.shared.trace.TraceIds;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 协议 message topic 消费器。
 *
 * <p>当前只接入营销发送闭环需要的 {@code message.send_result_reported}。
 * 其它 message 事件先跳过,避免未来协议层新增事件时误触发营销回写。</p>
 */
@Component
@Profile("kafka")
public class ProtocolMessageEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(ProtocolMessageEventConsumer.class);

    public static final String EVENT_MESSAGE_SEND_RESULT_REPORTED = "message.send_result_reported";
    public static final String EVENT_MESSAGE_ACK = "message.ack";
    private static final String SOURCE_GROUP_CREATION_MARKETING = "group_creation_marketing";
    private static final String SOURCE_HISTORICAL_GROUP_PULL = "historical_group_pull";
    private static final String SOURCE_HYPERLINK_TASK = "hyperlink_task";

    private final ObjectMapper objectMapper;
    private final List<ProtocolMessageSendResultReportedSink> sinks;
    private final List<ProtocolMessageAckSink> ackSinks;

    @Autowired
    public ProtocolMessageEventConsumer(ObjectMapper objectMapper,
                                        List<ProtocolMessageSendResultReportedSink> sinks,
                                        List<ProtocolMessageAckSink> ackSinks) {
        this.objectMapper = objectMapper;
        this.sinks = List.copyOf(sinks);
        this.ackSinks = List.copyOf(ackSinks);
    }

    /** 非 Spring 单测的存量构造兼容。 */
    public ProtocolMessageEventConsumer(ObjectMapper objectMapper,
            List<ProtocolMessageSendResultReportedSink> sinks) {
        this(objectMapper, sinks, List.of());
    }

    /**
     * 解析协议事件 envelope,识别发送结果事件后交给业务 sink 处理。
     *
     * @param rawMessage Kafka message value
     * @param headerTraceId Kafka trace header
     */
    @KafkaListener(
            topics = "${armada.protocol.kafka.message-events.topic:protocol.message.events.v1}",
            groupId = "${armada.protocol.kafka.message-events.group-id:armada-api-message-events}")
    public void onMessage(
            String rawMessage,
            @Header(name = TraceIds.KAFKA_HEADER, required = false) String headerTraceId) {
        JsonNode envelope = readEnvelope(rawMessage);
        try (TraceContext.Scope ignored = KafkaTraceSupport.open(
                envelope, headerTraceId, log, text(envelope, "eventId"))) {
            handleEnvelope(envelope);
        }
    }

    private void handleEnvelope(JsonNode envelope) {
        String eventType = text(envelope, "event");
        String eventId = text(envelope, "eventId");
        if (EVENT_MESSAGE_ACK.equals(eventType)) {
            ProtocolMessageAckEvent event = toAckEvent(envelope, dataNode(envelope));
            selectAckSink(event).handleAck(event);
            return;
        }
        if (!EVENT_MESSAGE_SEND_RESULT_REPORTED.equals(eventType)) {
            log.warn("协议消息事件暂未接入,跳过 eventId={} eventType={} accountId={} workerId={}",
                    eventId, eventType, text(envelope, "accountId"), text(envelope, "workerId"));
            return;
        }
        ProtocolMessageSendResultReportedEvent event = toSendResultReportedEvent(envelope, dataNode(envelope));
        log.info("协议消息发送结果事件收到 eventId={} tenantId={} taskId={} attemptId={} "
                        + "historicalExecutionId={} historicalMemberId={} success={} messageId={} workerId={}",
                event.eventId(), event.tenantId(), event.marketingTaskId(), event.attemptId(),
                event.historicalExecutionId(), event.historicalMemberId(), event.success(),
                event.messageId(), event.workerId());
        selectSink(event).handleSendResultReported(event);
    }

    /** 每个已识别来源必须且只能由一个 sink 负责，避免重复或遗漏业务回写。 */
    private ProtocolMessageSendResultReportedSink selectSink(ProtocolMessageSendResultReportedEvent event) {
        List<ProtocolMessageSendResultReportedSink> supported = sinks.stream()
                .filter(sink -> sink.supports(event))
                .toList();
        if (supported.size() != 1) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "协议消息发送结果必须匹配唯一处理器: source=" + event.source()
                            + ", matches=" + supported.size());
        }
        return supported.get(0);
    }

    private ProtocolMessageAckSink selectAckSink(ProtocolMessageAckEvent event) {
        List<ProtocolMessageAckSink> supported = ackSinks.stream()
                .filter(sink -> sink.supports(event)).toList();
        if (supported.size() != 1) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "协议消息 ACK 必须匹配唯一处理器: source=" + event.source()
                            + ", matches=" + supported.size());
        }
        return supported.get(0);
    }

    /** Kafka value 必须是协议事件 JSON envelope;非法 JSON 交给 Spring Kafka 重试/DLT。 */
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

    /** 将宽松 JSON envelope 收窄为营销发送结果事件,必要字段缺失时直接失败重试。 */
    private static ProtocolMessageSendResultReportedEvent toSendResultReportedEvent(JsonNode envelope, JsonNode data) {
        String source = text(data, "source");
        boolean groupCreationMarketing = SOURCE_GROUP_CREATION_MARKETING.equals(source);
        boolean historicalGroupPull = SOURCE_HISTORICAL_GROUP_PULL.equals(source);
        boolean hyperlinkTask = SOURCE_HYPERLINK_TASK.equals(source);
        return new ProtocolMessageSendResultReportedEvent(
                text(envelope, "eventId"),
                requiredLong(data, "tenantId", "协议消息发送结果事件缺少 data.tenantId"),
                groupCreationMarketing || historicalGroupPull || hyperlinkTask
                        ? longValue(data, "marketingTaskId")
                        : requiredLong(data, "marketingTaskId", "协议消息发送结果事件缺少 data.marketingTaskId"),
                groupCreationMarketing || historicalGroupPull || hyperlinkTask
                        ? longValue(data, "targetId")
                        : requiredLong(data, "targetId", "协议消息发送结果事件缺少 data.targetId"),
                groupCreationMarketing || historicalGroupPull || hyperlinkTask
                        ? longValue(data, "attemptId")
                        : requiredLong(data, "attemptId", "协议消息发送结果事件缺少 data.attemptId"),
                groupCreationMarketing || historicalGroupPull || hyperlinkTask
                        ? longValue(data, "roundNo")
                        : requiredLong(data, "roundNo", "协议消息发送结果事件缺少 data.roundNo"),
                requiredText(data, "protocolAccountId", "协议消息发送结果事件缺少 data.protocolAccountId"),
                hyperlinkTask ? null
                        : requiredText(data, "groupJid", "协议消息发送结果事件缺少 data.groupJid"),
                text(data, "commandId"),
                requiredBoolean(data, "success", "协议消息发送结果事件缺少 data.success"),
                text(data, "messageId"),
                text(data, "reasonCode"),
                text(data, "reasonMessage"),
                timestamp(envelope, data),
                text(envelope, "workerId"),
                groupCreationMarketing
                        ? requiredLong(data, "groupCreationTaskId", "协议消息发送结果事件缺少 data.groupCreationTaskId")
                        : longValue(data, "groupCreationTaskId"),
                groupCreationMarketing
                        ? requiredLong(data, "groupCreationItemId", "协议消息发送结果事件缺少 data.groupCreationItemId")
                        : longValue(data, "groupCreationItemId"),
                source,
                text(data, "groupStatus"),
                text(data, "groupStatusReason"),
                longValue(data, "groupStatusCheckedAt"),
                historicalGroupPull
                        ? requiredLong(data, "historicalExecutionId",
                                "协议消息发送结果事件缺少 data.historicalExecutionId")
                        : longValue(data, "historicalExecutionId"),
                historicalGroupPull
                        ? requiredLong(data, "historicalMemberId",
                                "协议消息发送结果事件缺少 data.historicalMemberId")
                        : longValue(data, "historicalMemberId"),
                firstText(data, "jid", "groupJid"),
                text(data, "targetKind"),
                hyperlinkTask
                        ? requiredLong(data, "hyperlinkTaskId",
                                "超链发送结果缺少 data.hyperlinkTaskId")
                        : longValue(data, "hyperlinkTaskId"),
                hyperlinkTask
                        ? requiredLong(data, "hyperlinkRecipientId",
                                "超链发送结果缺少 data.hyperlinkRecipientId")
                        : longValue(data, "hyperlinkRecipientId"),
                text(data, "outcome"), booleanValue(data, "terminal"));
    }

    private static ProtocolMessageAckEvent toAckEvent(JsonNode envelope, JsonNode data) {
        String source = requiredText(data, "source", "协议消息 ACK 缺少 data.source");
        String ackStatus = text(data, "ackStatus");
        if (ackStatus == null) {
            ackStatus = legacyAckStatus(text(data, "status"));
        } else {
            ackStatus = ackStatus.toUpperCase(java.util.Locale.ROOT);
        }
        if (!List.of("SUCCESS", "DELIVERED", "READ", "FAILED").contains(ackStatus)) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议消息 ACK 状态非法");
        }
        boolean hyperlink = SOURCE_HYPERLINK_TASK.equals(source);
        return new ProtocolMessageAckEvent(
                text(envelope, "eventId"),
                requiredLong(data, "tenantId", "协议消息 ACK 缺少 data.tenantId"),
                source,
                hyperlink ? requiredLong(data, "hyperlinkTaskId", "超链 ACK 缺少 data.hyperlinkTaskId")
                        : longValue(data, "hyperlinkTaskId"),
                hyperlink ? requiredLong(data, "hyperlinkRecipientId",
                                "超链 ACK 缺少 data.hyperlinkRecipientId")
                        : longValue(data, "hyperlinkRecipientId"),
                text(data, "commandId"),
                longValue(data, "accountId"),
                text(data, "protocolId"),
                requiredText(data, "protocolAccountId", "协议消息 ACK 缺少 data.protocolAccountId"),
                firstText(data, "jid", "groupJid"), text(data, "targetKind"),
                requiredText(data, "messageId", "协议消息 ACK 缺少 data.messageId"),
                ackStatus, booleanValue(data, "success"), text(data, "reasonCode"),
                text(data, "reasonMessage"), timestamp(envelope, data), text(envelope, "workerId"));
    }

    private static String legacyAckStatus(String status) {
        if (status == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议消息 ACK 缺少 ackStatus/status");
        }
        return switch (status.toLowerCase(java.util.Locale.ROOT)) {
            case "server", "success" -> "SUCCESS";
            case "delivery", "delivered" -> "DELIVERED";
            case "read", "played" -> "READ";
            case "error", "revoked", "failed" -> "FAILED";
            default -> throw new BusinessException(ErrorCode.VALIDATION, "协议消息 ACK status 非法");
        };
    }

    /** 兼容协议层 envelope.data 包裹格式;测试或临时工具也可直接传扁平字段。 */
    private static JsonNode dataNode(JsonNode envelope) {
        return envelope.path("data").isObject() ? envelope.path("data") : envelope;
    }

    /** 优先使用 data.timestamp;缺失时回退 envelope.occurredAt 并转成 epoch 毫秒。 */
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

    private static Boolean booleanValue(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) { return null; }
        return requiredBoolean(node, fieldName, "协议消息事件字段不是布尔值: " + fieldName);
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

    private static String firstText(JsonNode node, String first, String fallback) {
        String value = text(node, first);
        return value == null ? text(node, fallback) : value;
    }
}
