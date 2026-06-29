package com.armada.platform.kafka.consumer.account;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 协议账号事件 Kafka consumer。
 *
 * <p>当前接入 {@code account.state_changed} 和 {@code account.groups_reported}。
 * 其它账号事件先记录并跳过,
 * 防止一次性引入过多回写规则。</p>
 */
@Component
@Profile("kafka")
public class ProtocolAccountEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProtocolAccountEventConsumer.class);

    /** 现役协议层账号状态变更事件类型。 */
    public static final String EVENT_ACCOUNT_STATE_CHANGED = "account.state_changed";

    /** 协议层账号当前群列表回报事件类型。 */
    public static final String EVENT_ACCOUNT_GROUPS_REPORTED = "account.groups_reported";

    private final ObjectMapper objectMapper;
    private final ProtocolAccountStateChangedSink stateChangedSink;
    private final ProtocolAccountGroupsReportedSink groupsReportedSink;

    /**
     * 创建协议账号事件 consumer。
     *
     * @param objectMapper     JSON 解析器
     * @param stateChangedSink  账号状态变更下游处理口
     * @param groupsReportedSink 账号当前群列表下游处理口
     */
    public ProtocolAccountEventConsumer(ObjectMapper objectMapper,
                                        ProtocolAccountStateChangedSink stateChangedSink,
                                        ProtocolAccountGroupsReportedSink groupsReportedSink) {
        this.objectMapper = objectMapper;
        this.stateChangedSink = stateChangedSink;
        this.groupsReportedSink = groupsReportedSink;
    }

    /**
     * 消费协议账号事件消息。
     *
     * <p>Kafka value 是协议层 {@code EventEnvelope} JSON。方法保持 public 便于单测直接覆盖解析逻辑。</p>
     *
     * @param rawMessage Kafka message value
     */
    @KafkaListener(
            topics = "#{@protocolAccountEventConsumerProperties.topic}",
            groupId = "#{@protocolAccountEventConsumerProperties.groupId}")
    public void onMessage(String rawMessage) {
        JsonNode envelope = readEnvelope(rawMessage);
        String eventType = text(envelope, "event");
        String eventId = text(envelope, "eventId");
        if (EVENT_ACCOUNT_STATE_CHANGED.equals(eventType)) {
            ProtocolAccountStateChangedEvent event = toStateChangedEvent(envelope);
            log.info("协议账号状态事件收到 eventId={} accountId={} from={} to={} semantic={} rawCode={} workerId={}",
                    event.eventId(), event.protocolAccountId(), event.from(), event.to(),
                    event.semantic(), event.rawCode(), event.workerId());
            stateChangedSink.handleStateChanged(event);
            return;
        }
        if (EVENT_ACCOUNT_GROUPS_REPORTED.equals(eventType)) {
            ProtocolAccountGroupsReportedEvent event = toGroupsReportedEvent(envelope);
            log.info("协议账号群列表事件收到 eventId={} tenantId={} accountId={} protocolAccountId={} "
                            + "groupCount={} workerId={}",
                    event.eventId(), event.tenantId(), event.accountId(), event.protocolAccountId(),
                    event.groups().size(), event.workerId());
            groupsReportedSink.handleGroupsReported(event);
            return;
        }
        log.warn("协议账号事件暂未接入,跳过 eventId={} eventType={} accountId={} workerId={}",
                eventId, eventType, text(envelope, "accountId"), text(envelope, "workerId"));
    }

    private JsonNode readEnvelope(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议账号事件消息为空");
        }
        try {
            return objectMapper.readTree(rawMessage);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议账号事件 JSON 解析失败");
        }
    }

    private ProtocolAccountStateChangedEvent toStateChangedEvent(JsonNode envelope) {
        JsonNode data = envelope.path("data").isObject() ? envelope.path("data") : envelope;
        return new ProtocolAccountStateChangedEvent(
                text(envelope, "eventId"),
                requiredText(envelope, "accountId", "协议账号状态事件缺少 accountId"),
                text(data, "from"),
                requiredText(data, "to", "协议账号状态事件缺少 data.to"),
                occurredAt(envelope),
                text(data, "semantic"),
                integer(data, "rawCode"),
                text(envelope, "workerId"));
    }

    private ProtocolAccountGroupsReportedEvent toGroupsReportedEvent(JsonNode envelope) {
        JsonNode data = dataNode(envelope);
        Long tenantId = requiredLong(data, "tenantId", "协议账号群列表事件缺少 data.tenantId");
        Long accountId = requiredLong(data, "accountId", "协议账号群列表事件缺少 data.accountId");
        List<ProtocolAccountGroupsReportedEvent.Group> groups = groups(data.path("groups"));
        return new ProtocolAccountGroupsReportedEvent(
                text(envelope, "eventId"),
                tenantId,
                accountId,
                requiredText(envelope, "accountId", "协议账号群列表事件缺少 accountId"),
                occurredAt(envelope),
                text(envelope, "workerId"),
                groups);
    }

    private static JsonNode dataNode(JsonNode envelope) {
        return envelope.path("data").isObject() ? envelope.path("data") : envelope;
    }

    private static List<ProtocolAccountGroupsReportedEvent.Group> groups(JsonNode groupsNode) {
        if (!groupsNode.isArray()) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议账号群列表事件缺少 data.groups");
        }
        List<ProtocolAccountGroupsReportedEvent.Group> groups = new ArrayList<>(groupsNode.size());
        for (JsonNode node : groupsNode) {
            groups.add(new ProtocolAccountGroupsReportedEvent.Group(
                    requiredAnyText(node, "协议账号群列表事件缺少 groupJid", "groupJid", "jid", "id"),
                    anyText(node, "subject", "name"),
                    integerAny(node, "memberCount", "participantCount", "size"),
                    anyText(node, "ownerJid", "owner"),
                    anyText(node, "ownerPhone"),
                    boolAny(node, "isAdmin", "admin"),
                    boolAny(node, "announceOnly", "announce"),
                    anyText(node, "avatarUrl", "pictureUrl")));
        }
        return groups;
    }

    private static Long occurredAt(JsonNode envelope) {
        String value = text(envelope, "occurredAt");
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议账号事件 occurredAt 格式非法");
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
                throw new BusinessException(ErrorCode.VALIDATION, "协议账号事件字段不是整数: " + fieldName);
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "协议账号事件字段不是整数: " + fieldName);
    }

    private static Integer integerAny(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (!value.isMissingNode() && !value.isNull()) {
                return integer(node, fieldName);
            }
        }
        return null;
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
                throw new BusinessException(ErrorCode.VALIDATION, "协议账号事件字段不是长整数: " + fieldName);
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "协议账号事件字段不是长整数: " + fieldName);
    }

    private static Boolean boolAny(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (!value.isMissingNode() && !value.isNull()) {
                if (value.isBoolean()) {
                    return value.booleanValue();
                }
                if (value.isInt() || value.isLong()) {
                    return value.intValue() != 0;
                }
                if (value.isTextual() && !value.asText().isBlank()) {
                    String text = value.asText().trim();
                    if ("1".equals(text)) {
                        return true;
                    }
                    if ("0".equals(text)) {
                        return false;
                    }
                    return Boolean.valueOf(text);
                }
                throw new BusinessException(ErrorCode.VALIDATION, "协议账号事件字段不是布尔值: " + fieldName);
            }
        }
        return null;
    }

    private static String requiredText(JsonNode node, String fieldName, String errorMessage) {
        String value = text(node, fieldName);
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, errorMessage);
        }
        return value;
    }

    private static String requiredAnyText(JsonNode node, String errorMessage, String... fieldNames) {
        String value = anyText(node, fieldNames);
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, errorMessage);
        }
        return value;
    }

    private static String anyText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String value = text(node, fieldName);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText();
    }
}
