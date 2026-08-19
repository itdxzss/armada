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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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

    /** Web/Android 统一群成员变化事件类型；落 add/remove 在群事实与 promote/demote 角色事实。 */
    public static final String EVENT_GROUP_PARTICIPANT_CHANGED = "group.participant_changed";

    /** Web/Android 统一群资料字段级变更事件类型。 */
    public static final String EVENT_GROUP_METADATA_UPDATED = "group.metadata_updated";

    /** Web/Android 单群完整资料上报事件类型，用于首次建档与人工刷新。 */
    public static final String EVENT_GROUP_PROFILE_REPORTED = "group.profile_reported";

    /** 按需单群快照命令结算事件类型。 */
    public static final String EVENT_GROUP_SNAPSHOT_RESULT_REPORTED = "group.snapshot_result_reported";

    /** 单条资料事件 fieldMask 允许的最大字段数，取白名单全长即足够。 */
    private static final int MAX_METADATA_FIELDS = 16;

    /** 单群成员列表上限，与成员事件保持同一量级口径。 */
    private static final int MAX_SNAPSHOT_MEMBERS = 2000;

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

    /** 成员查询只允许当前普通拉群状态机实际使用的用途。 */
    private static final Set<String> SUPPORTED_MEMBER_QUERY_PURPOSES = Set.of(
            "MANAGER_JOIN_MEMBERSHIP", "MANAGER_ADMIN_MEMBERSHIP",
            "MANAGER_ADMIN_DISCOVERY",
            "SUPPLEMENT_PULLER_MEMBERSHIP", "SUPPLEMENT_MANAGER_MEMBERSHIP",
            "PULL_CALL_RECONCILIATION", "UNKNOWN_RESULT_RECONCILIATION");

    /** 成员查询事件只能由当前接入的两种协议后端发出。 */
    private static final Set<String> SUPPORTED_PROTOCOL_BACKENDS = Set.of("WEB", "ANDROID");

    /** 群快照失败只接受跨三端固定的低基数错误码。 */
    private static final Set<String> SUPPORTED_SNAPSHOT_ERROR_CODES = Set.of(
            "ACCOUNT_NOT_ONLINE", "ACCOUNT_BUSY", "ACCOUNT_BINDING_MISMATCH",
            "GROUP_PERMISSION_DENIED", "GROUP_NOT_JOINED", "GROUP_UNAVAILABLE",
            "INVALID_PAYLOAD", "TIMEOUT", "NETWORK", "RESULT_PUBLISH_FAILED",
            "PAYLOAD_TOO_LARGE", "UNKNOWN");

    /** Baileys/WGP2 当前会发布的群成员动作。 */
    private static final Set<String> SUPPORTED_PARTICIPANT_ACTIONS = Set.of(
            "add", "remove", "promote", "demote", "modify");

    /** 单条成员事件允许携带的最大成员数。 */
    private static final int MAX_PARTICIPANT_IDENTITIES = 500;

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

    /** 群成员变化下游处理边界。 */
    private final ProtocolGroupParticipantChangedSink participantChangedSink;

    /** 群资料字段级变更下游处理边界。 */
    private final ProtocolGroupMetadataUpdatedSink metadataUpdatedSink;

    /** 单群完整资料上报下游处理边界。 */
    private final ProtocolGroupProfileReportedSink profileReportedSink;

    /** 按需单群快照命令结算下游处理边界。 */
    private final ProtocolGroupSnapshotResultReportedSink snapshotResultReportedSink;

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
                                      ProtocolGroupInviteLinkChangedSink inviteLinkChangedSink,
                                      ProtocolGroupParticipantChangedSink participantChangedSink,
                                      ProtocolGroupMetadataUpdatedSink metadataUpdatedSink,
                                      ProtocolGroupProfileReportedSink profileReportedSink,
                                      ProtocolGroupSnapshotResultReportedSink snapshotResultReportedSink) {
        this.objectMapper = objectMapper;
        this.healthReportedSink = healthReportedSink;
        this.joinResultReportedSink = joinResultReportedSink;
        this.actionResultReportedSink = actionResultReportedSink;
        this.batchParticipantResultReportedSink = batchParticipantResultReportedSink;
        this.membersResultReportedSink = membersResultReportedSink;
        this.inviteLinkChangedSink = inviteLinkChangedSink;
        this.participantChangedSink = participantChangedSink;
        this.metadataUpdatedSink = metadataUpdatedSink;
        this.profileReportedSink = profileReportedSink;
        this.snapshotResultReportedSink = snapshotResultReportedSink;
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
            groupId = "${armada.protocol.kafka.group-events.group-id:armada-api-group-events}",
            concurrency = "${armada.protocol.kafka.group-events.concurrency:3}")
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
            case EVENT_GROUP_PARTICIPANT_CHANGED -> handleParticipantChanged(envelope, eventId);
            case EVENT_GROUP_METADATA_UPDATED -> handleMetadataUpdated(envelope, eventId);
            case EVENT_GROUP_PROFILE_REPORTED -> handleProfileReported(envelope, eventId);
            case EVENT_GROUP_SNAPSHOT_RESULT_REPORTED -> handleSnapshotResultReported(envelope, eventId);
            default -> log.warn("协议群组事件暂未接入,跳过 eventId={} eventType={} accountId={} workerId={}",
                    eventId, eventType, text(envelope, "accountId"), text(envelope, "workerId"));
        }
    }

    /** 校验结算关联和 scope 形状；事实是否已落库由 group 域按 commandId CAS 判断。 */
    private void handleSnapshotResultReported(JsonNode envelope, String eventId) {
        JsonNode data = dataNode(envelope);
        String protocolAccountId = requiredText(
                data, "protocolAccountId", "群快照结算缺少 data.protocolAccountId");
        if (!protocolAccountId.equals(text(envelope, "accountId"))) {
            throw validation("群快照结算账号关联不一致");
        }
        String backend = requiredText(data, "protocolBackend", "群快照结算缺少 backend")
                .toUpperCase(Locale.ROOT);
        if (!SUPPORTED_PROTOCOL_BACKENDS.contains(backend)) {
            throw validation("群快照结算 protocolBackend 非法");
        }
        String taskType = requiredText(data, "taskType", "群快照结算缺少 taskType");
        if (!Set.of("GROUP_METADATA_SYNC", "GROUP_BATCH_TASK_ITEM").contains(taskType)) {
            throw validation("群快照结算 taskType 非法");
        }
        JsonNode scopesNode = data.path("scopes");
        boolean invalidPayloadSettlement = isInvalidPayloadSettlement(scopesNode);
        if (!scopesNode.isObject() || scopesNode.isEmpty()
                || !invalidPayloadSettlement && scopesNode.size() > 2) {
            throw validation("群快照结算 scopes 非法");
        }
        Map<String, ProtocolGroupSnapshotResultReportedEvent.ScopeResult> scopes =
                new LinkedHashMap<>();
        scopesNode.fields().forEachRemaining(entry -> {
            String scope = entry.getKey();
            if ((!Set.of("METADATA", "INVITE_CODE").contains(scope) && !invalidPayloadSettlement)
                    || !entry.getValue().isObject()) {
                throw validation("群快照结算包含非法 scope");
            }
            JsonNode result = entry.getValue();
            String outcome = requiredText(result, "outcome", "群快照结算缺少 outcome")
                    .toUpperCase(Locale.ROOT);
            if (!Set.of("SUCCESS", "FAILED").contains(outcome)) {
                throw validation("群快照结算 outcome 非法");
            }
            long completedAt = requiredLong(result, "completedAt");
            String errorCode = text(result, "errorCode");
            if ("FAILED".equals(outcome) && !hasText(errorCode)) {
                throw validation("群快照失败结算缺少 errorCode");
            }
            if ("FAILED".equals(outcome) && !SUPPORTED_SNAPSHOT_ERROR_CODES.contains(errorCode)) {
                throw validation("群快照失败结算 errorCode 非法");
            }
            if ("SUCCESS".equals(outcome) && hasText(errorCode)) {
                throw validation("群快照成功结算不得携带 errorCode");
            }
            scopes.put(scope, new ProtocolGroupSnapshotResultReportedEvent.ScopeResult(
                    outcome, completedAt, errorCode));
        });
        int attemptNo = integer(data, "attemptNo") == null ? 0 : integer(data, "attemptNo");
        if (attemptNo <= 0) {
            throw validation("群快照结算 attemptNo 非法");
        }
        String rawGroupJid = text(data, "groupJid");
        String groupJid = rawGroupJid == null ? "" : rawGroupJid.toLowerCase(Locale.ROOT);
        if (!invalidPayloadSettlement && !groupJid.endsWith("@g.us")) {
            throw validation("群快照结算 groupJid 非法");
        }
        ProtocolGroupSnapshotResultReportedEvent event =
                new ProtocolGroupSnapshotResultReportedEvent(
                        requiredText(envelope, "eventId", "群快照结算缺少 eventId"),
                        requiredLong(data, "tenantId"), requiredLong(data, "accountId"),
                        protocolAccountId, backend, requiredLong(data, "groupLinkId"), groupJid,
                        taskType, requiredLong(data, "taskId"), attemptNo,
                        requiredText(data, "commandId", "群快照结算缺少 commandId"),
                        Map.copyOf(scopes), text(envelope, "workerId"));
        log.info("群快照结算收到 eventId={} taskType={} taskId={} attemptNo={} scopes={}",
                event.eventId(), event.taskType(), event.taskId(), event.attemptNo(), event.scopes().keySet());
        snapshotResultReportedSink.handleSnapshotResult(event);
    }

    private static boolean isInvalidPayloadSettlement(JsonNode scopesNode) {
        if (!scopesNode.isObject() || scopesNode.isEmpty()) {
            return false;
        }
        var fields = scopesNode.fields();
        while (fields.hasNext()) {
            JsonNode result = fields.next().getValue();
            if (!result.isObject()
                    || !"FAILED".equalsIgnoreCase(text(result, "outcome"))
                    || !"INVALID_PAYLOAD".equals(text(result, "errorCode"))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 校验并分派群成员变化事件。
     *
     * <p>{@code add/remove} 改在群与否，{@code promote/demote} 改角色，{@code modify} 合并同一个人的
     * PN/LID 身份，三类都必须带完整业务关联才能写库，因此走同一套校验。</p>
     */
    private void handleParticipantChanged(JsonNode envelope, String eventId) {
        JsonNode data = dataNode(envelope);
        String action = requiredText(
                data, "action", "协议群成员事件缺少 data.action").toLowerCase(Locale.ROOT);
        if (!SUPPORTED_PARTICIPANT_ACTIONS.contains(action)) {
            throw validation("协议群成员事件 action 非法");
        }
        String protocolAccountId = requiredText(
                data, "protocolAccountId", "协议群成员事件缺少 data.protocolAccountId");
        if (!protocolAccountId.equals(text(envelope, "accountId"))) {
            throw validation("协议群成员事件账号关联不一致");
        }
        String protocolBackend = requiredText(
                data, "protocolBackend", "协议群成员事件缺少 data.protocolBackend")
                .toUpperCase(Locale.ROOT);
        if (!SUPPORTED_PROTOCOL_BACKENDS.contains(protocolBackend)) {
            throw validation("协议群成员事件 protocolBackend 非法");
        }
        String groupJid = requiredText(
                data, "groupJid", "协议群成员事件缺少 data.groupJid")
                .trim().toLowerCase(Locale.ROOT);
        if (!groupJid.endsWith("@g.us")) {
            throw validation("协议群成员事件 groupJid 非法");
        }
        long occurredAt = requiredOccurredAt(envelope, data);
        ProtocolGroupParticipantChangedEvent event = new ProtocolGroupParticipantChangedEvent(
                requiredText(envelope, "eventId", "协议群成员事件缺少 eventId"),
                requiredLong(data, "tenantId"),
                requiredLong(data, "accountId"),
                protocolAccountId,
                protocolBackend,
                groupJid,
                action,
                participantIdentities(data.path("participants")),
                text(data, "operator"),
                requiredText(data, "source", "协议群成员事件缺少 data.source"),
                occurredAt,
                text(envelope, "workerId"));
        log.info("协议群成员事件收到 eventId={} tenantId={} accountId={} backend={} action={} count={}",
                event.eventId(), event.tenantId(), event.accountId(), event.protocolBackend(),
                event.action(), event.participants().size());
        participantChangedSink.handleParticipantChanged(event);
    }

    /**
     * 校验并分派群资料字段级变更事件。
     *
     * <p>fieldMask 决定哪些字段允许写库：进了 mask 的字段必须具有合法类型，未进 mask 的同名值
     * 一律忽略。业务白名单过滤放在 group 域完成——白名单枚举属于业务模型，platform 层不反向
     * 依赖它，这里只保证协议契约本身合法（数组、非空、去重、上限、字段类型）。</p>
     *
     * <p>类型非法一律拒绝整条事件而不是跳过该字段，避免部分写产生半截资料；未识别的字段名不在
     * 此处判定，由 group 域计指标后跳过，以免一个未知字段阻塞同一事件里已识别的字段。</p>
     */
    private void handleMetadataUpdated(JsonNode envelope, String eventId) {
        JsonNode data = dataNode(envelope);
        String protocolAccountId = requiredText(
                data, "protocolAccountId", "协议群资料事件缺少 data.protocolAccountId");
        if (!protocolAccountId.equals(text(envelope, "accountId"))) {
            throw validation("协议群资料事件账号关联不一致");
        }
        String protocolBackend = requiredText(
                data, "protocolBackend", "协议群资料事件缺少 data.protocolBackend")
                .toUpperCase(Locale.ROOT);
        if (!SUPPORTED_PROTOCOL_BACKENDS.contains(protocolBackend)) {
            throw validation("协议群资料事件 protocolBackend 非法");
        }
        String groupJid = requiredText(
                data, "groupJid", "协议群资料事件缺少 data.groupJid")
                .trim().toLowerCase(Locale.ROOT);
        if (!groupJid.endsWith("@g.us")) {
            throw validation("协议群资料事件 groupJid 非法");
        }
        long occurredAt = requiredOccurredAt(envelope, data, "协议群资料事件 occurredAt 非法");
        List<String> fieldMask = metadataFieldMask(data.path("fieldMask"));

        ProtocolGroupMetadataUpdatedEvent event = new ProtocolGroupMetadataUpdatedEvent(
                requiredText(envelope, "eventId", "协议群资料事件缺少 eventId"),
                requiredLong(data, "tenantId"),
                requiredLong(data, "accountId"),
                protocolAccountId,
                protocolBackend,
                groupJid,
                fieldMask,
                maskedText(data, fieldMask, "subject", true),
                maskedText(data, fieldMask, "description", false),
                maskedBoolean(data, fieldMask, "announceOnly"),
                maskedBoolean(data, fieldMask, "adminOnlyEditInfo"),
                maskedBoolean(data, fieldMask, "memberAddMode"),
                maskedBoolean(data, fieldMask, "joinApprovalMode"),
                maskedEphemeralDuration(data, fieldMask),
                text(data, "author"),
                requiredText(data, "source", "协议群资料事件缺少 data.source"),
                occurredAt,
                text(envelope, "workerId"));
        log.info("协议群资料事件收到 eventId={} tenantId={} accountId={} backend={} fields={}",
                event.eventId(), event.tenantId(), event.accountId(),
                event.protocolBackend(), event.fieldMask());
        metadataUpdatedSink.handleMetadataUpdated(event);
    }

    /** 解析 fieldMask：必须是字符串数组，去重后仍非空且不超上限。 */
    private static List<String> metadataFieldMask(JsonNode node) {
        if (!node.isArray() || node.isEmpty() || node.size() > MAX_METADATA_FIELDS) {
            throw validation("协议群资料事件 fieldMask 数量非法");
        }
        List<String> unique = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw validation("协议群资料事件 fieldMask 含非字符串字段名");
            }
            String name = item.asText().trim();
            if (!name.isEmpty() && !containsIgnoreCase(unique, name)) {
                unique.add(name);
            }
        }
        if (unique.isEmpty()) {
            throw validation("协议群资料事件 fieldMask 为空");
        }
        return List.copyOf(unique);
    }

    private static boolean containsIgnoreCase(List<String> names, String candidate) {
        for (String name : names) {
            if (name.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取进入 mask 的文本字段。
     *
     * @param requireValue true 表示进 mask 后必须有非空文本（群名没有"清空"语义）；false 表示
     *                     允许 null，用于描述的明确清空——此时值为 null 但字段仍在 mask 中，
     *                     下游据 mask 而非值本身判断是否写库
     */
    private static String maskedText(
            JsonNode data, List<String> mask, String fieldName, boolean requireValue) {
        if (!containsIgnoreCase(mask, fieldName)) {
            return null;
        }
        JsonNode value = data.path(fieldName);
        if (value.isTextual()) {
            String text = value.asText();
            if (requireValue && text.trim().isEmpty()) {
                throw validation("协议群资料事件 " + fieldName + " 不能为空");
            }
            return text;
        }
        if (requireValue || !(value.isNull() || value.isMissingNode())) {
            throw validation("协议群资料事件 " + fieldName + " 类型非法");
        }
        return null;
    }

    /** 读取进入 mask 的布尔字段；布尔设置没有"清空"语义，进了 mask 就必须有明确的 true/false。 */
    private static Boolean maskedBoolean(JsonNode data, List<String> mask, String fieldName) {
        if (!containsIgnoreCase(mask, fieldName)) {
            return null;
        }
        JsonNode value = data.path(fieldName);
        if (!value.isBoolean()) {
            throw validation("协议群资料事件 " + fieldName + " 必须是布尔值");
        }
        return value.booleanValue();
    }

    /** 读取进入 mask 的限时消息秒数；0 是明确关闭，负数与非整数一律拒绝。 */
    private static Integer maskedEphemeralDuration(JsonNode data, List<String> mask) {
        String fieldName = "ephemeralDurationSeconds";
        if (!containsIgnoreCase(mask, fieldName)) {
            return null;
        }
        JsonNode value = data.path(fieldName);
        if (!value.isIntegralNumber()
                || value.asLong() < 0
                || value.asLong() > Integer.MAX_VALUE) {
            throw validation("协议群资料事件 " + fieldName + " 必须是非负整数");
        }
        return value.intValue();
    }

    /**
     * 校验并分派单群完整资料上报事件。
     *
     * <p>资料字段沿用 {@code group.metadata_updated} 的 fieldMask 语义与类型校验；成员列表是本事件
     * 独有的部分，允许缺省（表示本次只观察资料），但一旦出现就必须每项至少有一个合法身份。</p>
     *
     * <p>{@code membersComplete} 缺省为 false：退群判定必须由协议明确授权，缺字段时按"不能判定"
     * 处理，宁可漏判也不误判——误判会把在群成员标记为已退群，直接影响选号与拉群。</p>
     */
    private void handleProfileReported(JsonNode envelope, String eventId) {
        JsonNode data = dataNode(envelope);
        String protocolAccountId = requiredText(
                data, "protocolAccountId", "协议群资料上报事件缺少 data.protocolAccountId");
        if (!protocolAccountId.equals(text(envelope, "accountId"))) {
            throw validation("协议群资料上报事件账号关联不一致");
        }
        String protocolBackend = requiredText(
                data, "protocolBackend", "协议群资料上报事件缺少 data.protocolBackend")
                .toUpperCase(Locale.ROOT);
        if (!SUPPORTED_PROTOCOL_BACKENDS.contains(protocolBackend)) {
            throw validation("协议群资料上报事件 protocolBackend 非法");
        }
        String groupJid = requiredText(
                data, "groupJid", "协议群资料上报事件缺少 data.groupJid")
                .trim().toLowerCase(Locale.ROOT);
        if (!groupJid.endsWith("@g.us")) {
            throw validation("协议群资料上报事件 groupJid 非法");
        }
        long occurredAt = requiredOccurredAt(envelope, data, "协议群资料上报事件 occurredAt 非法");
        List<String> fieldMask = metadataFieldMask(data.path("fieldMask"));
        List<ProtocolGroupProfileReportedEvent.Member> members =
                snapshotMembers(data.path("members"));
        boolean membersComplete = data.path("membersComplete").isBoolean()
                && data.path("membersComplete").booleanValue();
        if (membersComplete && members.isEmpty()) {
            // 声明完整却没有任何成员，会让控端把全群成员判为退群，必须拒绝而不是照做。
            throw validation("协议群资料上报事件声明成员完整但列表为空");
        }

        ProtocolGroupProfileReportedEvent event = new ProtocolGroupProfileReportedEvent(
                requiredText(envelope, "eventId", "协议群资料上报事件缺少 eventId"),
                requiredLong(data, "tenantId"),
                requiredLong(data, "accountId"),
                protocolAccountId,
                protocolBackend,
                groupJid,
                fieldMask,
                maskedText(data, fieldMask, "subject", true),
                maskedText(data, fieldMask, "description", false),
                maskedBoolean(data, fieldMask, "announceOnly"),
                maskedBoolean(data, fieldMask, "adminOnlyEditInfo"),
                maskedBoolean(data, fieldMask, "memberAddMode"),
                maskedBoolean(data, fieldMask, "joinApprovalMode"),
                maskedEphemeralDuration(data, fieldMask),
                members,
                membersComplete,
                requiredText(data, "source", "协议群资料上报事件缺少 data.source"),
                occurredAt,
                text(envelope, "workerId"),
                text(data, "commandId"));
        log.info("协议群资料上报事件收到 eventId={} tenantId={} accountId={} backend={} "
                        + "fields={} memberCount={} membersComplete={}",
                event.eventId(), event.tenantId(), event.accountId(), event.protocolBackend(),
                event.fieldMask(), event.members().size(), event.membersComplete());
        profileReportedSink.handleProfileReported(event);
    }

    /** 解析成员列表：允许缺省，出现则每项至少有一个合法身份。 */
    private static List<ProtocolGroupProfileReportedEvent.Member> snapshotMembers(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray() || node.size() > MAX_SNAPSHOT_MEMBERS) {
            throw validation("协议群资料上报事件 members 数量非法");
        }
        List<ProtocolGroupProfileReportedEvent.Member> members = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isObject()) {
                throw validation("协议群资料上报事件 member 非对象");
            }
            String jid = normalizedIdentity(text(item, "jid"), false);
            String lid = normalizedIdentity(text(item, "lid"), true);
            String phone = normalizedPhoneIdentity(text(item, "phone"));
            if (jid == null && lid == null && phone == null) {
                throw validation("协议群资料上报事件 member 缺少身份");
            }
            members.add(new ProtocolGroupProfileReportedEvent.Member(
                    jid, lid, phone,
                    booleanValue(item, "admin"),
                    booleanValue(item, "owner"),
                    text(item, "role")));
        }
        return List.copyOf(members);
    }

    private static long requiredOccurredAt(JsonNode envelope, JsonNode data, String message) {
        Long occurredAt = checkedAt(envelope, data);
        if (occurredAt == null || occurredAt <= 0) {
            throw validation(message);
        }
        return occurredAt;
    }

    private static long requiredOccurredAt(JsonNode envelope, JsonNode data) {
        Long occurredAt = checkedAt(envelope, data);
        if (occurredAt == null || occurredAt <= 0) {
            throw validation("协议群成员事件 occurredAt 非法");
        }
        return occurredAt;
    }

    private static List<ProtocolGroupParticipantIdentity> participantIdentities(JsonNode node) {
        if (!node.isArray() || node.isEmpty() || node.size() > MAX_PARTICIPANT_IDENTITIES) {
            throw validation("协议群成员事件 participants 数量非法");
        }
        List<ProtocolGroupParticipantIdentity> participants = new ArrayList<>(node.size());
        for (JsonNode participant : node) {
            if (!participant.isObject()) {
                throw validation("协议群成员事件 participant 非对象");
            }
            String id = normalizedIdentity(text(participant, "id"), false);
            String lid = normalizedIdentity(text(participant, "lid"), true);
            String phoneNumber = normalizedPhoneIdentity(text(participant, "phoneNumber"));
            if (id == null && lid == null && phoneNumber == null) {
                throw validation("协议群成员事件 participant 缺少身份");
            }
            participants.add(new ProtocolGroupParticipantIdentity(id, lid, phoneNumber));
        }
        return List.copyOf(participants);
    }

    private static String normalizedIdentity(String value, boolean lidOnly) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        boolean valid = lidOnly
                ? normalized.endsWith("@lid")
                : normalized.endsWith("@lid") || normalized.endsWith("@s.whatsapp.net");
        if (!valid) {
            throw validation("协议群成员事件 participant JID 非法");
        }
        return normalized;
    }

    private static String normalizedPhoneIdentity(String value) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith("@s.whatsapp.net")) {
            return normalized;
        }
        String digits = normalized.replaceAll("[^0-9]", "");
        if (digits.length() < 5 || digits.length() > 20) {
            throw validation("协议群成员事件 participant phoneNumber 非法");
        }
        return digits;
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
                text(envelope, "workerId"),
                text(data, "commandId"));
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
        boolean groupSettings = "pull_task_group_settings".equals(source)
                && "GROUP_SETTINGS_APPLY".equals(operation);
        if (!contactSave && !pullerInvite && !materialAdmin && !managerAdmin && !groupSettings) {
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
