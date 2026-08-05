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
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 协议账号事件 Kafka consumer。
 *
 * <p>状态和群同步事件使用独立 listener、topic 与 consumer group。类内继续复用既有
 * envelope 解析，避免两个入口产生不同的字段校验口径。</p>
 */
@Component
@Profile("kafka")
public class ProtocolAccountEventConsumer {

    private static final int MAX_DEPARTURE_PARTICIPANTS = 5_000;
    private static final int MAX_JOIN_PARTICIPANTS = 5_000;

    private static final Logger log = LoggerFactory.getLogger(ProtocolAccountEventConsumer.class);

    /** 现役协议层账号状态变更事件类型。 */
    public static final String EVENT_ACCOUNT_STATE_CHANGED = "account.state_changed";

    /** 协议层账号当前群列表回报事件类型。 */
    public static final String EVENT_ACCOUNT_GROUPS_REPORTED = "account.groups_reported";

    /** 协议层账号自身群关系精确变更事件类型。 */
    public static final String EVENT_ACCOUNT_GROUP_MEMBERSHIP_CHANGED =
            "account.group_membership_changed";

    /** Android HistorySync 历史退群成员上报。 */
    public static final String EVENT_ACCOUNT_GROUP_PAST_PARTICIPANTS_REPORTED =
            "account.group_past_participants.reported";

    /** Android 实时群成员退群上报。 */
    public static final String EVENT_ACCOUNT_GROUP_PARTICIPANT_DEPARTED =
            "account.group_participant_departed";

    /** Android 实时群成员进群上报。 */
    public static final String EVENT_ACCOUNT_GROUP_PARTICIPANT_JOINED =
            "account.group_participant_joined";

    /** 协议层单群详情同步请求事件类型。 */
    public static final String EVENT_ACCOUNT_GROUP_METADATA_SYNC_REQUESTED =
            "account.group_metadata_sync_requested";

    private static final Set<String> GROUP_METADATA_SYNC_TRIGGERS =
            Set.of("PARTICIPANT_CHANGED", "METADATA_CHANGED");

    /** 协议层账号离线诊断事件类型。 */
    public static final String EVENT_ACCOUNT_OFFLINE_DIAGNOSED = "account.offline_diagnosed";

    private final ObjectMapper objectMapper;
    private final ProtocolAccountStateChangedSink stateChangedSink;
    private final ProtocolAccountGroupsReportedSink groupsReportedSink;
    private final ProtocolAccountOfflineDiagnosedSink offlineDiagnosedSink;
    private final ProtocolAccountGroupEventSinks groupEventSinks;
    private final ProtocolGroupMetadataSyncRequestedSink metadataSyncRequestedSink;

    /**
     * 创建协议账号事件 consumer。
     *
     * @param objectMapper     JSON 解析器
     * @param stateChangedSink  账号状态变更下游处理口
     * @param groupsReportedSink 账号当前群列表下游处理口
     * @param offlineDiagnosedSink 账号离线诊断下游处理口
     * @param groupEventSinks 群关系与普通成员退群事实下游处理入口
     * @param metadataSyncRequestedSink 单群详情同步请求下游处理口
     */
    public ProtocolAccountEventConsumer(ObjectMapper objectMapper,
                                        ProtocolAccountStateChangedSink stateChangedSink,
                                        ProtocolAccountGroupsReportedSink groupsReportedSink,
                                        ProtocolAccountOfflineDiagnosedSink offlineDiagnosedSink,
                                        ProtocolAccountGroupEventSinks groupEventSinks,
                                        ProtocolGroupMetadataSyncRequestedSink metadataSyncRequestedSink) {
        this.objectMapper = objectMapper;
        this.stateChangedSink = stateChangedSink;
        this.groupsReportedSink = groupsReportedSink;
        this.offlineDiagnosedSink = offlineDiagnosedSink;
        this.groupEventSinks = groupEventSinks;
        this.metadataSyncRequestedSink = metadataSyncRequestedSink;
    }

    /**
     * 消费协议账号事件消息。
     *
     * <p>Kafka value 是协议层 {@code EventEnvelope} JSON。方法保持 public 便于单测直接覆盖解析逻辑。</p>
     *
     * @param rawMessage Kafka message value
     */
    @KafkaListener(
            topics = "${armada.protocol.kafka.account-state-events.topic:protocol.account.state.events.v1}",
            groupId = "${armada.protocol.kafka.account-state-events.group-id:armada-api-account-state-events}",
            concurrency = "${armada.protocol.kafka.account-state-events.concurrency:4}")
    public void onStateMessage(String rawMessage) {
        JsonNode envelope = readEnvelope(rawMessage);
        String eventType = text(envelope, "event");
        if (EVENT_ACCOUNT_STATE_CHANGED.equals(eventType)) {
            ProtocolAccountStateChangedEvent event = toStateChangedEvent(envelope);
            log.info("协议账号状态事件收到 eventId={} tenantId={} accountId={} protocolAccountId={} "
                            + "from={} to={} semantic={} rawCode={} attemptId={} workerId={}",
                    event.eventId(), event.tenantId(), event.accountId(), event.protocolAccountId(),
                    event.from(), event.to(), event.semantic(), event.rawCode(), event.onlineAttemptId(),
                    event.workerId());
            stateChangedSink.handleStateChanged(event);
            return;
        }
        if (EVENT_ACCOUNT_OFFLINE_DIAGNOSED.equals(eventType)) {
            ProtocolAccountOfflineDiagnosedEvent event = toOfflineDiagnosedEvent(envelope);
            log.info("协议账号离线诊断事件收到 eventId={} tenantId={} accountId={} protocolAccountId={} "
                            + "attemptId={} diagnosisCode={} rawCode={} workerId={}",
                    event.eventId(), event.tenantId(), event.accountId(), event.protocolAccountId(),
                    event.onlineAttemptId(), event.diagnosisCode(), event.rawCode(), event.workerId());
            offlineDiagnosedSink.handleOfflineDiagnosed(event);
            return;
        }
        throw new BusinessException(ErrorCode.VALIDATION,
                "协议账号状态 Topic 收到非法事件类型: " + String.valueOf(eventType));
    }

    /**
     * 消费账号群快照与本人群关系变化事件。
     *
     * @param rawMessage Kafka message value
     */
    @KafkaListener(
            topics = "${armada.protocol.kafka.account-group-sync-events.topic:protocol.account.group-sync.events.v1}",
            groupId = "${armada.protocol.kafka.account-group-sync-events.group-id:armada-api-account-group-sync-events}",
            concurrency = "${armada.protocol.kafka.account-group-sync-events.concurrency:4}")
    public void onGroupSyncMessage(String rawMessage) {
        JsonNode envelope = readEnvelope(rawMessage);
        String eventType = text(envelope, "event");
        if (EVENT_ACCOUNT_GROUPS_REPORTED.equals(eventType)) {
            ProtocolAccountGroupsReportedEvent event = toGroupsReportedEvent(envelope);
            log.info("协议账号群列表事件收到 eventId={} tenantId={} accountId={} protocolAccountId={} "
                            + "source={} reportedAt={} groupCount={} snapshotComplete={} skippedGroupCount={} workerId={}",
                    event.eventId(), event.tenantId(), event.accountId(), event.protocolAccountId(),
                    event.source(), event.reportedAt(), event.groups().size(), event.snapshotComplete(),
                    event.skippedGroupCount(), event.workerId());
            groupsReportedSink.handleGroupsReported(event);
            return;
        }
        if (EVENT_ACCOUNT_GROUP_MEMBERSHIP_CHANGED.equals(eventType)) {
            ProtocolAccountGroupMembershipChangedEvent event = toMembershipChangedEvent(envelope);
            log.info("协议账号群关系事件收到 eventId={} accountId={} action={} source={} workerId={}",
                    event.eventId(), event.accountId(), event.action(), event.source(), event.workerId());
            groupEventSinks.handleMembershipChanged(event);
            return;
        }
        if (EVENT_ACCOUNT_GROUP_PAST_PARTICIPANTS_REPORTED.equals(eventType)
                || EVENT_ACCOUNT_GROUP_PARTICIPANT_DEPARTED.equals(eventType)) {
            ProtocolGroupDepartureEvent event = toGroupDepartureEvent(envelope, eventType);
            log.info("协议群退群事实收到 eventId={} accountId={} source={} participantCount={}",
                    event.eventId(), event.accountId(), event.sourceType(), event.participants().size());
            groupEventSinks.handleDepartures(event);
            return;
        }
        if (EVENT_ACCOUNT_GROUP_PARTICIPANT_JOINED.equals(eventType)) {
            ProtocolGroupJoinEvent event = toGroupJoinEvent(envelope);
            log.info("协议群进群事实收到 eventId={} accountId={} source={} participantCount={}",
                    event.eventId(), event.accountId(), event.sourceType(), event.participants().size());
            groupEventSinks.handleJoins(event);
            return;
        }
        if (EVENT_ACCOUNT_GROUP_METADATA_SYNC_REQUESTED.equals(eventType)) {
            ProtocolGroupMetadataSyncRequestedEvent event = toGroupMetadataSyncRequestedEvent(envelope);
            log.info("协议群详情同步请求收到 eventId={} tenantId={} accountId={} groupJid={} trigger={} workerId={}",
                    event.eventId(), event.tenantId(), event.accountId(), event.groupJid(),
                    event.trigger(), event.workerId());
            metadataSyncRequestedSink.handleGroupMetadataSyncRequested(event);
            return;
        }
        throw new BusinessException(ErrorCode.VALIDATION,
                "协议账号群同步 Topic 收到非法事件类型: " + String.valueOf(eventType));
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
                requiredLong(data, "tenantId", "协议账号状态事件缺少 data.tenantId"),
                requiredLong(data, "accountId", "协议账号状态事件缺少 data.accountId"),
                requiredText(envelope, "accountId", "协议账号状态事件缺少 accountId"),
                text(data, "from"),
                requiredText(data, "to", "协议账号状态事件缺少 data.to"),
                occurredAt(envelope),
                text(data, "semantic"),
                integer(data, "rawCode"),
                text(data, "source"),
                text(data, "onlineAttemptId"),
                longValue(data, "proxyId"),
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
                text(data, "source"),
                bool(data, "snapshotComplete"),
                integer(data, "skippedGroupCount"),
                text(envelope, "workerId"),
                groups);
    }

    /**
     * 将协议事件 envelope 转换为账号自身群关系事件。
     *
     * <p>顶层 {@code accountId} 是协议账号事件的 Kafka 路由键，必须存在并与
     * {@code data.protocolAccountId} 完全一致；否则事件可能脱离原账号分区顺序，必须拒绝而不能继续回写。</p>
     *
     * @param envelope 协议层账号事件 envelope
     * @return 完成必要字段和路由一致性校验后的精确关系事件
     * @throws BusinessException 当 data、路由账号、事件 ID、租户、账号、群 JID、动作、本人分类或事实时间缺失，
     *                           或路由账号与数据账号不一致时抛出
     */
    private ProtocolAccountGroupMembershipChangedEvent toMembershipChangedEvent(JsonNode envelope) {
        JsonNode data = dataNode(envelope);
        String routedProtocolAccountId = requiredText(
                envelope, "accountId", "协议账号群关系事件缺少 accountId");
        String protocolAccountId = requiredText(
                data, "protocolAccountId", "协议账号群关系事件缺少 data.protocolAccountId");
        if (!routedProtocolAccountId.equals(protocolAccountId)) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议账号群关系事件路由账号不一致");
        }
        Long occurredAt = occurredAt(envelope);
        if (occurredAt == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议账号群关系事件缺少 occurredAt");
        }
        return new ProtocolAccountGroupMembershipChangedEvent(
                requiredText(envelope, "eventId", "协议账号群关系事件缺少 eventId"),
                requiredLong(data, "tenantId", "协议账号群关系事件缺少 data.tenantId"),
                requiredLong(data, "accountId", "协议账号群关系事件缺少 data.accountId"),
                protocolAccountId,
                requiredText(data, "groupJid", "协议账号群关系事件缺少 data.groupJid"),
                requiredText(data, "action", "协议账号群关系事件缺少 data.action"),
                requiredText(data, "selfParticipation", "协议账号群关系事件缺少 data.selfParticipation"),
                occurredAt,
                text(data, "source"),
                text(envelope, "workerId"));
    }

    private ProtocolGroupMetadataSyncRequestedEvent toGroupMetadataSyncRequestedEvent(JsonNode envelope) {
        JsonNode data = dataNode(envelope);
        String routedProtocolAccountId = requiredText(
                envelope, "accountId", "协议群详情同步事件缺少 accountId");
        String protocolAccountId = requiredText(
                data, "protocolAccountId", "协议群详情同步事件缺少 data.protocolAccountId");
        if (!routedProtocolAccountId.equals(protocolAccountId)) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群详情同步事件路由账号不一致");
        }
        long tenantId = requiredPositiveLong(
                data, "tenantId", "协议群详情同步事件 data.tenantId 非法");
        long accountId = requiredPositiveLong(
                data, "accountId", "协议群详情同步事件 data.accountId 非法");
        String groupJid = requiredText(
                data, "groupJid", "协议群详情同步事件缺少 data.groupJid");
        if (!groupJid.endsWith("@g.us")) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群详情同步事件 groupJid 非法");
        }
        String trigger = requiredText(
                data, "trigger", "协议群详情同步事件缺少 data.trigger");
        if (!GROUP_METADATA_SYNC_TRIGGERS.contains(trigger)) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群详情同步事件 trigger 非法");
        }
        Long eventOccurredAt = occurredAt(envelope);
        if (eventOccurredAt == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群详情同步事件缺少 occurredAt");
        }
        return new ProtocolGroupMetadataSyncRequestedEvent(
                requiredText(envelope, "eventId", "协议群详情同步事件缺少 eventId"),
                tenantId,
                accountId,
                protocolAccountId,
                groupJid,
                trigger,
                eventOccurredAt,
                text(data, "source"),
                text(envelope, "workerId"));
    }

    private ProtocolAccountOfflineDiagnosedEvent toOfflineDiagnosedEvent(JsonNode envelope) {
        JsonNode data = dataNode(envelope);
        String evidenceJson = null;
        JsonNode evidence = envelope.path("evidence").isObject() ? envelope.path("evidence") : data.path("evidence");
        if (evidence.isObject()) {
            evidenceJson = evidence.toString();
        }
        return new ProtocolAccountOfflineDiagnosedEvent(
                text(envelope, "eventId"),
                requiredLong(data, "tenantId", "协议账号离线诊断事件缺少 data.tenantId"),
                requiredLong(data, "accountId", "协议账号离线诊断事件缺少 data.accountId"),
                requiredText(data, "protocolAccountId", "协议账号离线诊断事件缺少 data.protocolAccountId"),
                requiredText(data, "onlineAttemptId", "协议账号离线诊断事件缺少 data.onlineAttemptId"),
                text(data, "previousOnlineAttemptId"),
                text(data, "commandId"),
                text(data, "batchId"),
                longValue(data, "proxyId"),
                text(data, "source"),
                text(data, "from"),
                requiredText(data, "to", "协议账号离线诊断事件缺少 data.to"),
                requiredText(data, "diagnosisCode", "协议账号离线诊断事件缺少 data.diagnosisCode"),
                requiredText(data, "diagnosisClass", "协议账号离线诊断事件缺少 data.diagnosisClass"),
                integer(data, "rawCode"),
                text(data, "rawReason"),
                text(data, "recoverability"),
                text(data, "actionTaken"),
                occurredAt(envelope),
                text(envelope, "workerId"),
                evidenceJson);
    }

    private ProtocolGroupDepartureEvent toGroupDepartureEvent(JsonNode envelope, String eventType) {
        JsonNode data = dataNode(envelope);
        String routedProtocolAccountId = requiredText(
                envelope, "accountId", "协议群退群事件缺少 accountId");
        String protocolAccountId = requiredText(
                data, "protocolAccountId", "协议群退群事件缺少 data.protocolAccountId");
        if (!routedProtocolAccountId.equals(protocolAccountId)) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群退群事件路由账号不一致");
        }
        String source = requiredText(data, "source", "协议群退群事件缺少 data.source");
        String expectedSource = EVENT_ACCOUNT_GROUP_PAST_PARTICIPANTS_REPORTED.equals(eventType)
                ? "HISTORY_SYNC" : "WGP2_NOTIFICATION";
        if (!expectedSource.equals(source)) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群退群事件类型与来源不一致");
        }
        JsonNode participantNodes = data.path("participants");
        if (!participantNodes.isArray() || participantNodes.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群退群事件缺少 data.participants");
        }
        if (participantNodes.size() > MAX_DEPARTURE_PARTICIPANTS) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群退群事件 participants 数量超限");
        }
        String groupJid = requiredBoundedText(
                        data, "groupJid", 128, "协议群退群事件缺少 data.groupJid")
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
        if (!groupJid.matches("^[^@\\s]+@g\\.us$")) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群退群事件 groupJid 非法");
        }
        List<ProtocolGroupDepartureEvent.Participant> participants = new ArrayList<>();
        for (JsonNode participant : participantNodes) {
            String participantJid = requiredBoundedText(
                            participant, "participantJid", 191, "协议群退群事件缺少 participantJid")
                    .trim()
                    .toLowerCase(java.util.Locale.ROOT);
            if (!participantJid.matches("^[0-9]+(?::[0-9]+)?@(s\\.whatsapp\\.net|lid)$")) {
                throw new BusinessException(ErrorCode.VALIDATION, "协议群退群事件 participantJid 非法");
            }
            String phone = boundedText(participant, "phone", 32, "协议群退群事件 phone 长度超限");
            if (phone != null) {
                String phoneDigits = phone.replaceAll("[^0-9]", "");
                if (phoneDigits.length() < 5 || phoneDigits.length() > 20) {
                    throw new BusinessException(ErrorCode.VALIDATION, "协议群退群事件 phone 非法");
                }
            }
            String exitType = requiredText(participant, "exitType", "协议群退群事件缺少 exitType");
            if (!"LEFT".equals(exitType) && !"REMOVED".equals(exitType)) {
                throw new BusinessException(ErrorCode.VALIDATION, "协议群退群事件退出方式非法");
            }
            Long exitedAt = requiredLong(participant, "exitedAt", "协议群退群事件缺少 exitedAt");
            if (exitedAt <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION, "协议群退群事件 exitedAt 非法");
            }
            participants.add(new ProtocolGroupDepartureEvent.Participant(
                    participantJid,
                    phone,
                    exitType,
                    exitedAt,
                    requiredBoundedText(
                            participant, "sourceEventId", 255, "协议群退群事件缺少 sourceEventId")));
        }
        return new ProtocolGroupDepartureEvent(
                requiredText(envelope, "eventId", "协议群退群事件缺少 eventId"),
                requiredLong(data, "tenantId", "协议群退群事件缺少 data.tenantId"),
                requiredLong(data, "accountId", "协议群退群事件缺少 data.accountId"),
                protocolAccountId,
                groupJid,
                source,
                occurredAt(envelope),
                List.copyOf(participants));
    }

    private ProtocolGroupJoinEvent toGroupJoinEvent(JsonNode envelope) {
        JsonNode data = dataNode(envelope);
        String routedProtocolAccountId = requiredText(
                envelope, "accountId", "协议群进群事件缺少 accountId");
        String protocolAccountId = requiredText(
                data, "protocolAccountId", "协议群进群事件缺少 data.protocolAccountId");
        if (!routedProtocolAccountId.equals(protocolAccountId)) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群进群事件路由账号不一致");
        }
        String source = requiredText(data, "source", "协议群进群事件缺少 data.source");
        if (!"WGP2_NOTIFICATION".equals(source)) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群进群事件来源非法");
        }
        JsonNode participantNodes = data.path("joinedParticipants");
        if (!participantNodes.isArray() || participantNodes.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群进群事件缺少 data.joinedParticipants");
        }
        if (participantNodes.size() > MAX_JOIN_PARTICIPANTS) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群进群事件 joinedParticipants 数量超限");
        }
        String groupJid = requiredJoinText(
                        data, "groupJid", 128, "协议群进群事件缺少 data.groupJid")
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
        if (!groupJid.matches("^[^@\\s]+@g\\.us$")) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群进群事件 groupJid 非法");
        }
        List<ProtocolGroupJoinEvent.Participant> participants = new ArrayList<>();
        for (JsonNode participant : participantNodes) {
            String participantJid = requiredJoinText(
                            participant, "participantJid", 191,
                            "协议群进群事件缺少 participantJid")
                    .trim()
                    .toLowerCase(java.util.Locale.ROOT);
            if (!participantJid.matches("^[0-9]+(?::[0-9]+)?@(s\\.whatsapp\\.net|lid)$")) {
                throw new BusinessException(ErrorCode.VALIDATION, "协议群进群事件 participantJid 非法");
            }
            String phone = boundedText(participant, "phone", 32, "协议群进群事件 phone 长度超限");
            if (phone != null) {
                String phoneDigits = phone.replaceAll("[^0-9]", "");
                if (phoneDigits.length() < 5 || phoneDigits.length() > 20) {
                    throw new BusinessException(ErrorCode.VALIDATION, "协议群进群事件 phone 非法");
                }
            }
            Long joinedAt = requiredLong(participant, "joinedAt", "协议群进群事件缺少 joinedAt");
            if (joinedAt <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION, "协议群进群事件 joinedAt 非法");
            }
            participants.add(new ProtocolGroupJoinEvent.Participant(
                    participantJid,
                    phone,
                    joinedAt,
                    requiredJoinText(
                            participant, "sourceEventId", 255,
                            "协议群进群事件缺少 sourceEventId")));
        }
        return new ProtocolGroupJoinEvent(
                requiredText(envelope, "eventId", "协议群进群事件缺少 eventId"),
                requiredLong(data, "tenantId", "协议群进群事件缺少 data.tenantId"),
                requiredLong(data, "accountId", "协议群进群事件缺少 data.accountId"),
                protocolAccountId,
                groupJid,
                source,
                occurredAt(envelope),
                List.copyOf(participants));
    }

    private static String requiredJoinText(
            JsonNode node,
            String field,
            int maxLength,
            String missingMessage) {
        String value = requiredText(node, field, missingMessage);
        if (value.length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群进群事件 " + field + " 长度超限");
        }
        return value;
    }

    private static String requiredBoundedText(
            JsonNode node,
            String field,
            int maxLength,
            String missingMessage) {
        String value = requiredText(node, field, missingMessage);
        if (value.length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议群退群事件 " + field + " 长度超限");
        }
        return value;
    }

    private static String boundedText(JsonNode node, String field, int maxLength, String message) {
        String value = text(node, field);
        if (value != null && value.length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION, message);
        }
        return value;
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

    private static long requiredPositiveLong(JsonNode node, String fieldName, String errorMessage) {
        Long value = requiredLong(node, fieldName, errorMessage);
        if (value <= 0) {
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

    private static Boolean bool(JsonNode node, String fieldName) {
        return boolAny(node, fieldName);
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
