package com.armada.platform.kafka.consumer.group;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 协议群组事件 Kafka consumer。
 *
 * <p>接入群健康和 Web/Android 统一进群结果事件。消费层负责 JSON 类型、必填字段和信封账号一致性
 * 校验，不负责业务重试。其它群组事件只记录并跳过，防止尚未定义回写口径的事件阻塞同 topic。</p>
 */
@Component
@Profile("kafka")
public class ProtocolGroupEventConsumer {

    /** 记录消费路由和脱敏后的业务关联字段。 */
    private static final Logger log = LoggerFactory.getLogger(ProtocolGroupEventConsumer.class);

    /** 协议层群链接健康检测回报事件类型。 */
    public static final String EVENT_GROUP_HEALTH_REPORTED = "group.health_reported";

    /** Web/Android 统一进群结果事件类型。 */
    public static final String EVENT_GROUP_JOIN_RESULT_REPORTED = "group.join_result_reported";

    /** Web/Android 统一群动作结果事件类型。 */
    public static final String EVENT_GROUP_ACTION_RESULT_REPORTED = "group.action_result_reported";

    /** 协议两端约定的完整进群结果码集合，未知值必须拒绝，不能误判为普通失败。 */
    private static final Set<String> SUPPORTED_JOIN_OUTCOMES = Set.of(
            "JOINED", "ALREADY_JOINED", "PENDING_APPROVAL", "FAILED");

    /** 群动作结果只允许明确成功或明确失败。 */
    private static final Set<String> SUPPORTED_ACTION_OUTCOMES = Set.of("SUCCESS", "FAILED", "UNKNOWN");

    /** Kafka 事件 JSON 解析器。 */
    private final ObjectMapper objectMapper;

    /** 群健康事件下游处理边界。 */
    private final ProtocolGroupHealthReportedSink healthReportedSink;

    /** 统一进群结果下游处理边界。 */
    private final ProtocolGroupJoinResultReportedSink joinResultReportedSink;

    /** 统一群动作结果下游处理边界。 */
    private final ProtocolGroupActionResultReportedSink actionResultReportedSink;

    /** 普通链接批量拉人逐成员结果下游处理边界。 */
    private final ProtocolPullTaskBatchParticipantResultReportedSink batchParticipantResultReportedSink;

    /**
     * 创建协议群组事件 consumer。
     *
     * @param objectMapper       JSON 解析器
     * @param healthReportedSink 群组健康检测下游处理口
     * @param joinResultReportedSink Web/Android 统一进群结果下游处理口
     * @param actionResultReportedSink Web/Android 统一群动作结果下游处理口
     * @param batchParticipantResultReportedSink 批量拉人逐成员结果下游处理口
     */
    public ProtocolGroupEventConsumer(ObjectMapper objectMapper,
                                      ProtocolGroupHealthReportedSink healthReportedSink,
                                      ProtocolGroupJoinResultReportedSink joinResultReportedSink,
                                      ProtocolGroupActionResultReportedSink actionResultReportedSink,
                                      ProtocolPullTaskBatchParticipantResultReportedSink
                                              batchParticipantResultReportedSink) {
        this.objectMapper = objectMapper;
        this.healthReportedSink = healthReportedSink;
        this.joinResultReportedSink = joinResultReportedSink;
        this.actionResultReportedSink = actionResultReportedSink;
        this.batchParticipantResultReportedSink = batchParticipantResultReportedSink;
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
        switch (eventType == null ? "" : eventType) {
            case EVENT_GROUP_HEALTH_REPORTED -> handleHealthReported(envelope, eventId);
            case EVENT_GROUP_JOIN_RESULT_REPORTED -> handleJoinResultReported(envelope, eventId);
            case EVENT_GROUP_ACTION_RESULT_REPORTED -> handleActionResultReported(envelope, eventId);
            default -> log.warn("协议群组事件暂未接入,跳过 eventId={} eventType={} accountId={} workerId={}",
                    eventId, eventType, text(envelope, "accountId"), text(envelope, "workerId"));
        }
    }

    /** 校验拉群账号动作结果的完整关联字段并传给任务状态机。 */
    private void handleActionResultReported(JsonNode envelope, String eventId) {
        JsonNode data = dataNode(envelope);
        String source = requiredText(data, "source", "协议群动作结果缺少 data.source");
        String operation = requiredText(data, "operation", "协议群动作结果缺少 data.operation");
        if ("pull_task_batch_add".equals(source)) {
            handleBatchParticipantResultReported(envelope, data, eventId, operation);
            return;
        }
        Long tenantId = requiredLong(data, "tenantId");
        Long pullTaskId = requiredLong(data, "pullTaskId");
        Long groupExecutionId = requiredLong(data, "groupExecutionId");
        Long actionId = requiredLong(data, "actionId");
        boolean contactSave = "pull_task_contact_save".equals(source)
                && "CONTACT_SAVE".equals(operation);
        boolean pullerInvite = "pull_task_puller_invite".equals(source)
                && "PARTICIPANT_ADD".equals(operation);
        boolean materialAdmin = "pull_task_material_admin".equals(source)
                && "PARTICIPANT_PROMOTE".equals(operation);
        if (!contactSave && !pullerInvite && !materialAdmin) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群动作结果来源或动作非法");
        }
        Long accountId = requiredLong(data, "accountId");
        String protocolAccountId = requiredText(
                data, "protocolAccountId", "协议群动作结果缺少 data.protocolAccountId");
        String envelopeAccountId = text(envelope, "accountId");
        if (envelopeAccountId != null && !envelopeAccountId.equals(protocolAccountId)) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群动作结果账号关联不一致");
        }
        String commandId = requiredText(data, "commandId", "协议群动作结果缺少 data.commandId");
        Integer attemptNo = integer(data, "attemptNo");
        if (attemptNo == null || attemptNo <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群动作结果 attemptNo 非法");
        }
        String outcome = requiredText(data, "outcome", "协议群动作结果缺少 data.outcome")
                .toUpperCase(Locale.ROOT);
        if (!SUPPORTED_ACTION_OUTCOMES.contains(outcome)) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群动作结果 outcome 非法");
        }
        String targetJid = text(data, "targetJid");
        if ((pullerInvite || materialAdmin) && (targetJid == null || targetJid.isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议成员结果缺少 data.targetJid");
        }
        Boolean retryable = booleanValue(data, "retryable");
        if (retryable == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群动作结果缺少 data.retryable");
        }
        Long timestamp = longValue(data, "timestamp");
        ProtocolGroupActionResultReportedEvent event = new ProtocolGroupActionResultReportedEvent(
                eventId, tenantId, pullTaskId, groupExecutionId, actionId, source, operation,
                accountId, protocolAccountId, commandId, attemptNo, outcome,
                targetJid, text(data, "reasonCode"), text(data, "reasonMessage"), retryable,
                timestamp == null ? 0L : timestamp, text(envelope, "workerId"));
        log.info("协议群动作结果收到 eventId={} tenantId={} actionId={} commandId={} outcome={}",
                event.eventId(), event.tenantId(), event.actionId(), event.commandId(), event.outcome());
        actionResultReportedSink.handleActionResultReported(event);
    }

    /** 校验批量拉人的单成员结果并传给任务状态机。 */
    private void handleBatchParticipantResultReported(
            JsonNode envelope,
            JsonNode data,
            String eventId,
            String operation) {
        if (!"PARTICIPANT_ADD".equals(operation)) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议批量拉人动作非法");
        }
        Long tenantId = requiredLong(data, "tenantId");
        Long pullTaskId = requiredLong(data, "pullTaskId");
        Long groupExecutionId = requiredLong(data, "groupExecutionId");
        Long pullCallId = requiredLong(data, "pullCallId");
        Long accountId = requiredLong(data, "accountId");
        String protocolAccountId = requiredText(
                data, "protocolAccountId", "协议批量拉人结果缺少 data.protocolAccountId");
        String envelopeAccountId = text(envelope, "accountId");
        if (envelopeAccountId != null && !envelopeAccountId.equals(protocolAccountId)) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议批量拉人结果账号关联不一致");
        }
        String commandId = requiredText(data, "commandId", "协议批量拉人结果缺少 data.commandId");
        Integer attemptNo = integer(data, "attemptNo");
        if (attemptNo == null || attemptNo <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议批量拉人结果 attemptNo 非法");
        }
        String targetJid = requiredText(data, "targetJid", "协议批量拉人结果缺少 data.targetJid");
        String outcome = requiredText(data, "outcome", "协议批量拉人结果缺少 data.outcome")
                .toUpperCase(Locale.ROOT);
        if (!SUPPORTED_ACTION_OUTCOMES.contains(outcome)) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议批量拉人结果 outcome 非法");
        }
        Boolean retryable = booleanValue(data, "retryable");
        if (retryable == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议批量拉人结果缺少 data.retryable");
        }
        Long timestamp = longValue(data, "timestamp");
        ProtocolPullTaskBatchParticipantResultReportedEvent event =
                new ProtocolPullTaskBatchParticipantResultReportedEvent(
                        eventId, tenantId, pullTaskId, groupExecutionId, pullCallId,
                        accountId, protocolAccountId, commandId, attemptNo, targetJid, outcome,
                        text(data, "reasonCode"), text(data, "reasonMessage"), retryable,
                        timestamp == null ? 0L : timestamp, text(envelope, "workerId"));
        log.info("协议批量拉人单成员结果收到 eventId={} tenantId={} pullCallId={} commandId={} outcome={}",
                event.eventId(), event.tenantId(), event.pullCallId(), event.commandId(), event.outcome());
        batchParticipantResultReportedSink.handleBatchParticipantResultReported(event);
    }

    /** 群健康沿用允许缺少业务主键时记录并跳过的兼容策略。 */
    private void handleHealthReported(JsonNode envelope, String eventId) {
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

    /**
     * 校验统一进群结果的业务关联字段并传给任务状态机。
     *
     * <p>进群结果会推进账号 lane，因此关联字段不完整时必须抛出消费异常，不能像观察型健康事件一样
     * 静默跳过。真正的重复与迟到判断由任务域在行锁内完成。</p>
     */
    private void handleJoinResultReported(JsonNode envelope, String eventId) {
        JsonNode data = dataNode(envelope);
        Long tenantId = requiredLong(data, "tenantId");
        ProtocolGroupJoinCorrelation correlation = joinCorrelation(data);
        Long accountId = requiredLong(data, "accountId");
        String protocolAccountId = requiredText(
                data, "protocolAccountId", "协议进群结果缺少 data.protocolAccountId");
        String envelopeAccountId = text(envelope, "accountId");
        if (envelopeAccountId != null && !envelopeAccountId.equals(protocolAccountId)) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议进群结果账号关联不一致");
        }
        String commandId = requiredText(data, "commandId", "协议进群结果缺少 data.commandId");
        Integer attemptNo = integer(data, "attemptNo");
        if (attemptNo == null || attemptNo <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议进群结果 attemptNo 非法");
        }
        String outcome = requiredText(data, "outcome", "协议进群结果缺少 data.outcome")
                .toUpperCase(Locale.ROOT);
        if (!SUPPORTED_JOIN_OUTCOMES.contains(outcome)) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议进群结果 outcome 非法");
        }
        Boolean retryable = booleanValue(data, "retryable");
        if (retryable == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议进群结果缺少 data.retryable");
        }
        Long timestamp = longValue(data, "timestamp");
        ProtocolGroupJoinResultReportedEvent event = new ProtocolGroupJoinResultReportedEvent(
                eventId,
                tenantId,
                correlation,
                accountId,
                protocolAccountId,
                commandId,
                attemptNo,
                outcome,
                text(data, "groupJid"),
                text(data, "reasonCode"),
                text(data, "reasonMessage"),
                retryable,
                timestamp == null ? 0L : timestamp,
                text(envelope, "workerId"));
        log.info("协议进群结果收到 eventId={} tenantId={} correlation={} commandId={} attemptNo={} outcome={}",
                event.eventId(), event.tenantId(), event.correlation(),
                event.commandId(), event.attemptNo(), event.outcome());
        joinResultReportedSink.handleJoinResultReported(event);
    }

    private static ProtocolGroupJoinCorrelation joinCorrelation(JsonNode data) {
        String source = text(data, "source");
        if (source == null || "join_task".equals(source)) {
            return new ProtocolJoinTaskGroupJoinCorrelation(
                    requiredLong(data, "joinTaskId"),
                    requiredLong(data, "joinTaskResultId"));
        }
        if ("pull_task_manager_join".equals(source)) {
            return new ProtocolPullTaskGroupJoinCorrelation(
                    requiredLong(data, "pullTaskId"),
                    requiredLong(data, "groupExecutionId"),
                    requiredLong(data, "actionId"));
        }
        throw new BusinessException(ErrorCode.VALIDATION, "协议进群结果 source 非法");
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

    private static Long requiredLong(JsonNode node, String fieldName) {
        Long value = longValue(node, fieldName);
        if (value == null || value <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群组事件缺少或非法字段: " + fieldName);
        }
        return value;
    }

    private static Boolean booleanValue(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isTextual() && ("true".equalsIgnoreCase(value.asText())
                || "false".equalsIgnoreCase(value.asText()))) {
            return Boolean.valueOf(value.asText());
        }
        throw new BusinessException(ErrorCode.VALIDATION, "协议群组事件字段不是布尔值: " + fieldName);
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
