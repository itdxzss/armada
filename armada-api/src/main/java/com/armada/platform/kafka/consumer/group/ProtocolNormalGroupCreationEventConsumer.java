package com.armada.platform.kafka.consumer.group;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** 新建普群统一结果专用 Kafka consumer。 */
@Component
@Profile("kafka")
public class ProtocolNormalGroupCreationEventConsumer {

    /** 新建普群结果复用通用群动作结果事件定义。 */
    public static final String EVENT_GROUP_ACTION_RESULT_REPORTED =
            "group.action_result_reported";

    private static final String SOURCE_NORMAL_GROUP_CREATION = "normal_group_creation";
    private static final Set<String> SUPPORTED_ACTIONS = Set.of(
            "CONTACT_PREPARE", "GROUP_CREATE", "GROUP_SETTINGS_APPLY", "GROUP_LEAVE");
    private static final Set<String> SUPPORTED_OUTCOMES = Set.of("SUCCESS", "FAILED", "UNKNOWN");

    private final ObjectMapper objectMapper;
    private final ProtocolNormalGroupCreationResultReportedSink resultReportedSink;

    public ProtocolNormalGroupCreationEventConsumer(
            ObjectMapper objectMapper,
            ProtocolNormalGroupCreationResultReportedSink resultReportedSink) {
        this.objectMapper = objectMapper;
        this.resultReportedSink = resultReportedSink;
    }

    /** 消费 Web/Android 回传到专用结果 topic 的新建普群 action 最终结果。 */
    @KafkaListener(
            topics = "${armada.normal-group-creation.kafka.result-topic:protocol.normal-group.events.v1}",
            groupId = "${armada.normal-group-creation.kafka.result-group-id:armada-api-normal-group-results}",
            concurrency = "${armada.normal-group-creation.kafka.result-concurrency:4}")
    public void onMessage(String rawMessage) {
        JsonNode envelope = readEnvelope(rawMessage);
        String eventType = requiredText(envelope, "event", "新建普群结果信封缺少 event");
        if (!EVENT_GROUP_ACTION_RESULT_REPORTED.equals(eventType)) {
            throw new BusinessException(ErrorCode.VALIDATION, "新建普群结果事件类型非法");
        }
        JsonNode data = dataNode(envelope);
        String source = requiredText(data, "source", "新建普群结果缺少 data.source");
        if (!SOURCE_NORMAL_GROUP_CREATION.equals(source)) {
            throw new BusinessException(ErrorCode.VALIDATION, "新建普群结果来源非法");
        }
        handleResult(envelope, data, text(envelope, "eventId"));
    }

    private void handleResult(JsonNode envelope, JsonNode data, String eventId) {
        String action = requiredText(data, "operation", "新建普群结果缺少 action");
        if (!SUPPORTED_ACTIONS.contains(action)) {
            throw new BusinessException(ErrorCode.VALIDATION, "新建普群结果 action 非法");
        }
        Long tenantId = requiredLong(data, "tenantId");
        Long taskId = requiredLong(data, "taskId");
        Long itemId = requiredLong(data, "itemId");
        Long memberId = longValue(data, "memberId");
        String direction = text(data, "direction");
        if ("CONTACT_PREPARE".equals(action)) {
            if (memberId == null || memberId <= 0
                    || !("CREATOR_SAVE_MEMBER".equals(direction)
                    || "MEMBER_SAVE_CREATOR".equals(direction))) {
                throw new BusinessException(ErrorCode.VALIDATION, "联系人准备结果缺少成员或方向");
            }
        } else if (memberId != null || direction != null) {
            throw new BusinessException(ErrorCode.VALIDATION, "非联系人动作不能携带成员方向");
        }
        Long accountId = requiredLong(data, "accountId");
        String protocolAccountId = requiredText(
                data, "protocolAccountId", "新建普群结果缺少 data.protocolAccountId");
        String envelopeAccountId = requiredText(
                envelope, "accountId", "新建普群结果信封缺少 accountId");
        if (!envelopeAccountId.equals(protocolAccountId)) {
            throw new BusinessException(ErrorCode.VALIDATION, "新建普群结果账号关联不一致");
        }
        String backend = requiredText(data, "protocolBackend", "新建普群结果缺少协议后端")
                .toUpperCase(Locale.ROOT);
        if (!Set.of("WEB", "ANDROID").contains(backend)) {
            throw new BusinessException(ErrorCode.VALIDATION, "新建普群结果协议后端非法");
        }
        String commandId = requiredText(data, "commandId", "新建普群结果缺少 commandId");
        Integer attemptNo = integer(data, "attemptNo");
        if (attemptNo == null || attemptNo <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "新建普群结果 attemptNo 非法");
        }
        String outcome = requiredText(data, "outcome", "新建普群结果缺少 outcome")
                .toUpperCase(Locale.ROOT);
        if (!SUPPORTED_OUTCOMES.contains(outcome)) {
            throw new BusinessException(ErrorCode.VALIDATION, "新建普群结果 outcome 非法");
        }
        String groupJid = text(data, "groupJid");
        if (!"GROUP_CREATE".equals(action) && groupJid != null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION, "仅建群动作结果允许携带 groupJid");
        }
        if ("GROUP_CREATE".equals(action) && "SUCCESS".equals(outcome)
                && (groupJid == null || groupJid.isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION, "建群成功结果缺少 groupJid");
        }
        Boolean retryable = booleanValue(data, "retryable");
        if (retryable == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "新建普群结果缺少 retryable");
        }
        Long timestamp = longValue(data, "timestamp");
        resultReportedSink.handleNormalGroupCreationResult(
                new ProtocolNormalGroupCreationResultReportedEvent(
                        eventId, tenantId, taskId, itemId, memberId, direction, action,
                        accountId, protocolAccountId, backend, commandId, attemptNo, outcome,
                        groupJid, text(data, "reasonCode"), text(data, "reasonMessage"),
                        retryable, timestamp == null ? 0L : timestamp, text(envelope, "workerId")));
    }

    private JsonNode readEnvelope(String rawMessage) {
        try {
            JsonNode envelope = objectMapper.readTree(rawMessage);
            if (envelope == null || !envelope.isObject()) {
                throw new BusinessException(ErrorCode.VALIDATION, "新建普群结果必须是 JSON 对象");
            }
            return envelope;
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "新建普群结果 JSON 非法");
        }
    }

    private static JsonNode dataNode(JsonNode envelope) {
        JsonNode data = envelope.get("data");
        if (data == null || !data.isObject()) {
            throw new BusinessException(ErrorCode.VALIDATION, "新建普群结果缺少 data");
        }
        return data;
    }

    private static String requiredText(JsonNode node, String field, String message) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, message);
        }
        return value;
    }

    private static Long requiredLong(JsonNode node, String field) {
        Long value = longValue(node, field);
        if (value == null || value <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "新建普群结果 " + field + " 非法");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        return value.asText();
    }

    private static Long longValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.canConvertToLong()) {
            return value.longValue();
        }
        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer integer(JsonNode node, String field) {
        Long value = longValue(node, field);
        if (value == null || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            return null;
        }
        return value.intValue();
    }

    private static Boolean booleanValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isBoolean()) {
            return null;
        }
        return value.booleanValue();
    }
}
