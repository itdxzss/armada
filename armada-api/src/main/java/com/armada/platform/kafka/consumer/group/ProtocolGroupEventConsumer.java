package com.armada.platform.kafka.consumer.group;

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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
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

    /** Web/Android 统一群成员查询结果事件类型。 */
    public static final String EVENT_GROUP_MEMBERS_RESULT_REPORTED = "group.members.result_reported";

    /** Web/Android 统一群邀请链接变更事件类型。 */
    public static final String EVENT_GROUP_INVITE_LINK_CHANGED = "group.invite_link_changed";

    /** 协议两端约定的完整进群结果码集合，未知值必须拒绝，不能误判为普通失败。 */
    private static final Set<String> SUPPORTED_JOIN_OUTCOMES = Set.of(
            "JOINED", "ALREADY_JOINED", "PENDING_APPROVAL", "FAILED");

    /** 群动作结果只允许明确成功或明确失败。 */
    private static final Set<String> SUPPORTED_ACTION_OUTCOMES = Set.of("SUCCESS", "FAILED", "UNKNOWN");

    /** 批量拉人逐号码执行阶段使用大小写敏感的固定协议值。 */
    private static final Set<String> SUPPORTED_PARTICIPANT_EXECUTION_STATES = Set.of(
            "NOT_STARTED", "STARTED", "UNCERTAIN");

    /** 成员查询只允许成功或明确失败。 */
    private static final Set<String> SUPPORTED_MEMBER_QUERY_OUTCOMES = Set.of("SUCCESS", "FAILED");

    /** 成员查询只允许当前普通拉群状态机实际使用的六类用途。 */
    private static final Set<String> SUPPORTED_MEMBER_QUERY_PURPOSES = Set.of(
            "MANAGER_JOIN_MEMBERSHIP", "MANAGER_ADMIN_MEMBERSHIP",
            "SUPPLEMENT_PULLER_MEMBERSHIP", "SUPPLEMENT_MANAGER_MEMBERSHIP",
            "PULL_CALL_RECONCILIATION", "UNKNOWN_RESULT_RECONCILIATION");

    /** 成员查询事件只能由当前接入的两种协议后端发出。 */
    private static final Set<String> SUPPORTED_PROTOCOL_BACKENDS = Set.of("WEB", "ANDROID");

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

    /** 普通拉群异步成员查询结果下游处理边界。 */
    private final ProtocolGroupMembersResultReportedSink membersResultReportedSink;

    /** 群邀请链接变更下游处理边界。 */
    private final ProtocolGroupInviteLinkChangedSink inviteLinkChangedSink;

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
                                              batchParticipantResultReportedSink,
                                      ProtocolGroupMembersResultReportedSink membersResultReportedSink,
                                      ProtocolGroupInviteLinkChangedSink inviteLinkChangedSink) {
        this.objectMapper = objectMapper;
        this.healthReportedSink = healthReportedSink;
        this.joinResultReportedSink = joinResultReportedSink;
        this.actionResultReportedSink = actionResultReportedSink;
        this.batchParticipantResultReportedSink = batchParticipantResultReportedSink;
        this.membersResultReportedSink = membersResultReportedSink;
        this.inviteLinkChangedSink = inviteLinkChangedSink;
    }

    /**
     * 消费协议群组事件消息。
     *
     * <p>Kafka value 是协议层 {@code EventEnvelope} JSON。方法保持 public 便于单测直接覆盖解析逻辑。</p>
     *
     * @param rawMessage Kafka message value
     * @param headerTraceId Kafka trace header
     */
    @KafkaListener(
            topics = "${armada.protocol.kafka.group-events.topic:protocol.group.events.v1}",
            groupId = "${armada.protocol.kafka.group-events.group-id:armada-api-group-events}")
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
        switch (eventType == null ? "" : eventType) {
            case EVENT_GROUP_HEALTH_REPORTED -> handleHealthReported(envelope, eventId);
            case EVENT_GROUP_JOIN_RESULT_REPORTED -> handleJoinResultReported(envelope, eventId);
            case EVENT_GROUP_ACTION_RESULT_REPORTED -> handleActionResultReported(envelope, eventId);
            case EVENT_GROUP_MEMBERS_RESULT_REPORTED -> handleMembersResultReported(envelope, eventId);
            case EVENT_GROUP_INVITE_LINK_CHANGED -> handleInviteLinkChanged(envelope, eventId);
            default -> log.warn("协议群组事件暂未接入,跳过 eventId={} eventType={} accountId={} workerId={}",
                    eventId, eventType, text(envelope, "accountId"), text(envelope, "workerId"));
        }
    }

    /** 校验当前邀请码事件并交给 group 域按租户和群 JID 幂等保存。 */
    private void handleInviteLinkChanged(JsonNode envelope, String eventId) {
        JsonNode data = dataNode(envelope);
        String protocolAccountId = requiredText(
                data, "protocolAccountId", "协议群邀请链接事件缺少 data.protocolAccountId");
        if (!protocolAccountId.equals(text(envelope, "accountId"))) {
            throw validation("协议群邀请链接事件账号关联不一致");
        }
        String protocolBackend = requiredText(
                data, "protocolBackend", "协议群邀请链接事件缺少 data.protocolBackend")
                .toUpperCase(Locale.ROOT);
        if (!SUPPORTED_PROTOCOL_BACKENDS.contains(protocolBackend)) {
            throw validation("协议群邀请链接事件 protocolBackend 非法");
        }
        String inviteCode = requiredText(
                data, "inviteCode", "协议群邀请链接事件缺少 data.inviteCode");
        if (inviteCode.length() > 64 || !inviteCode.matches("[A-Za-z0-9_-]+")) {
            throw validation("协议群邀请链接事件 inviteCode 非法");
        }
        Long occurredAt = checkedAt(envelope, data);
        if (occurredAt == null || occurredAt <= 0) {
            throw validation("协议群邀请链接事件 occurredAt 非法");
        }
        ProtocolGroupInviteLinkChangedEvent event = new ProtocolGroupInviteLinkChangedEvent(
                requiredText(envelope, "eventId", "协议群邀请链接事件缺少 eventId"),
                requiredLong(data, "tenantId"),
                requiredLong(data, "accountId"),
                protocolAccountId,
                protocolBackend,
                requiredText(data, "groupJid", "协议群邀请链接事件缺少 data.groupJid"),
                inviteCode,
                text(data, "author"),
                requiredText(data, "source", "协议群邀请链接事件缺少 data.source"),
                occurredAt,
                text(envelope, "workerId"));
        log.info("协议群邀请链接变更收到 eventId={} tenantId={} accountId={} protocolBackend={}",
                event.eventId(), event.tenantId(), event.accountId(), event.protocolBackend());
        inviteLinkChangedSink.handleInviteLinkChanged(event);
    }

    /** 校验异步成员查询的完整关联与事实结构，再交给任务域做当前尝试 CAS。 */
    private void handleMembersResultReported(JsonNode envelope, String eventId) {
        JsonNode data = dataNode(envelope);
        if (!"pull_task_member_query".equals(requiredText(
                data, "source", "协议成员查询结果缺少 data.source"))) {
            throw validation("协议成员查询结果 source 非法");
        }
        long tenantId = requiredLong(data, "tenantId");
        long pullTaskId = requiredLong(data, "pullTaskId");
        long groupExecutionId = requiredLong(data, "groupExecutionId");
        long queryId = requiredLong(data, "queryId");
        String purpose = requiredText(data, "purpose", "协议成员查询结果缺少 data.purpose");
        if (!SUPPORTED_MEMBER_QUERY_PURPOSES.contains(purpose)) {
            throw validation("协议成员查询结果 purpose 非法");
        }
        long accountId = requiredLong(data, "accountId");
        String protocolAccountId = requiredText(
                data, "protocolAccountId", "协议成员查询结果缺少 data.protocolAccountId");
        if (!protocolAccountId.equals(text(envelope, "accountId"))) {
            throw validation("协议成员查询结果账号关联不一致");
        }
        String protocolBackend = requiredText(
                data, "protocolBackend", "协议成员查询结果缺少 data.protocolBackend");
        if (!SUPPORTED_PROTOCOL_BACKENDS.contains(protocolBackend)) {
            throw validation("协议成员查询结果 protocolBackend 非法");
        }
        String commandId = requiredText(data, "commandId", "协议成员查询结果缺少 data.commandId");
        Integer attemptNo = integer(data, "attemptNo");
        if (attemptNo == null || attemptNo <= 0) {
            throw validation("协议成员查询结果 attemptNo 非法");
        }
        String outcome = requiredText(data, "outcome", "协议成员查询结果缺少 data.outcome")
                .toUpperCase(Locale.ROOT);
        if (!SUPPORTED_MEMBER_QUERY_OUTCOMES.contains(outcome)) {
            throw validation("协议成员查询结果 outcome 非法");
        }
        String groupJid = requiredText(data, "groupJid", "协议成员查询结果缺少 data.groupJid");
        List<ProtocolGroupMemberFact> members = memberFacts(data.path("members"), outcome);
        Boolean retryable = booleanValue(data, "retryable");
        if (retryable == null) {
            throw validation("协议成员查询结果缺少 data.retryable");
        }
        long timestamp = requiredLong(data, "timestamp");
        ProtocolGroupMembersResultReportedEvent event = new ProtocolGroupMembersResultReportedEvent(
                eventId, tenantId, pullTaskId, groupExecutionId, queryId, purpose,
                accountId, protocolAccountId, protocolBackend, commandId, attemptNo,
                outcome, groupJid, members, text(data, "reasonCode"),
                text(data, "reasonMessage"), retryable, timestamp,
                text(envelope, "workerId"));
        log.info("协议群成员查询结果收到 eventId={} tenantId={} queryId={} commandId={} outcome={}",
                event.eventId(), event.tenantId(), event.queryId(), event.commandId(), event.outcome());
        membersResultReportedSink.handleMembersResultReported(event);
    }

    private static List<ProtocolGroupMemberFact> memberFacts(JsonNode node, String outcome) {
        if (!node.isArray()) {
            throw validation("协议成员查询结果 members 非数组");
        }
        if ("FAILED".equals(outcome) && !node.isEmpty()) {
            throw validation("协议成员查询失败结果不能携带成员事实");
        }
        List<ProtocolGroupMemberFact> members = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        for (JsonNode fact : node) {
            if (!fact.isObject()) {
                throw validation("协议成员查询结果 member 非对象");
            }
            String targetJid = requiredText(
                    fact, "targetJid", "协议成员查询结果缺少 member.targetJid");
            if (!targets.add(targetJid)) {
                throw validation("协议成员查询结果 targetJid 重复");
            }
            Boolean inGroup = booleanValue(fact, "inGroup");
            Boolean admin = booleanValue(fact, "admin");
            if (inGroup == null || admin == null || admin && !inGroup) {
                throw validation("协议成员查询结果成员事实非法");
            }
            String participantJid = text(fact, "participantJid");
            String phoneNumber = text(fact, "phoneNumber");
            if (!inGroup && (hasText(participantJid) || hasText(phoneNumber))) {
                throw validation("协议成员查询结果非群成员携带身份事实");
            }
            members.add(new ProtocolGroupMemberFact(
                    targetJid, participantJid, phoneNumber, inGroup, admin));
        }
        return List.copyOf(members);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
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
        boolean managerAdmin = "pull_task_manager_admin".equals(source)
                && "PARTICIPANT_PROMOTE".equals(operation);
        if (!contactSave && !pullerInvite && !materialAdmin && !managerAdmin) {
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
        if ((pullerInvite || materialAdmin || managerAdmin)
                && (targetJid == null || targetJid.isBlank())) {
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
        String executionState = requiredText(
                data, "executionState", "协议批量拉人结果缺少 data.executionState");
        if (!SUPPORTED_PARTICIPANT_EXECUTION_STATES.contains(executionState)
                || !validParticipantResultState(outcome, executionState)) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议批量拉人结果 executionState 非法");
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
                        executionState,
                        text(data, "reasonCode"), text(data, "reasonMessage"), retryable,
                        timestamp == null ? 0L : timestamp, text(envelope, "workerId"));
        log.info("协议批量拉人单成员结果收到 eventId={} tenantId={} pullCallId={} commandId={} outcome={}",
                event.eventId(), event.tenantId(), event.pullCallId(), event.commandId(), event.outcome());
        batchParticipantResultReportedSink.handleBatchParticipantResultReported(event);
    }

    /** 只允许协议端约定的四种 outcome × executionState 组合。 */
    private static boolean validParticipantResultState(String outcome, String executionState) {
        return switch (outcome) {
            case "SUCCESS", "FAILED" -> "STARTED".equals(executionState);
            case "UNKNOWN" -> "NOT_STARTED".equals(executionState)
                    || "UNCERTAIN".equals(executionState);
            default -> false;
        };
    }

    /** 群健康允许实时通知缺少链接主键，由群域按租户和群 JID 解析。 */
    private void handleHealthReported(JsonNode envelope, String eventId) {
        JsonNode data = dataNode(envelope);
        Long tenantId = longValue(data, "tenantId");
        Long groupLinkId = longValue(data, "groupLinkId");
        String groupJid = text(data, "groupJid");
        if (tenantId == null || groupJid == null || groupJid.isBlank()) {
            log.warn("协议群组健康事件缺少租户或群JID,跳过 eventId={} tenantId={} groupLinkId={} groupJid={}",
                    eventId, tenantId, groupLinkId, groupJid);
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
