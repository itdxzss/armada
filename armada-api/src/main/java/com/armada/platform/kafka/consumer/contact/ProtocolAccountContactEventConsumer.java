package com.armada.platform.kafka.consumer.contact;

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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 协议账号通讯录快照事件 Kafka consumer。
 *
 * <p>快照是大消息，走独立 topic 且 {@code max.poll.records=1}，照群同步的既定做法。
 * envelope 解析工具与既有两个消费器各自持有一份，不抽公共基类，保持仓库既有形状。</p>
 */
@Component
@Profile("kafka")
public class ProtocolAccountContactEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProtocolAccountContactEventConsumer.class);

    /** 协议层账号通讯录快照分片事件类型。 */
    public static final String EVENT_ACCOUNT_CONTACTS_REPORTED = "account.contacts_reported";

    private final ObjectMapper objectMapper;
    private final AccountContactsReportedSink sink;

    /**
     * @param objectMapper JSON 解析器
     * @param sink 快照分片处理器
     */
    public ProtocolAccountContactEventConsumer(ObjectMapper objectMapper,
                                               AccountContactsReportedSink sink) {
        this.objectMapper = objectMapper;
        this.sink = sink;
    }

    /**
     * 消费账号通讯录快照分片事件。
     *
     * @param rawMessage Kafka message value
     * @param headerTraceId Kafka trace header
     */
    @KafkaListener(
            topics = "${armada.protocol.kafka.account-contact-events.topic:protocol.account.contact-sync.events.v1}",
            groupId = "${armada.protocol.kafka.account-contact-events.group-id:armada-api-account-contact-events}",
            concurrency = "${armada.protocol.kafka.account-contact-events.concurrency:2}",
            properties = "max.poll.records=${armada.protocol.kafka.account-contact-events.max-poll-records:1}")
    public void onMessage(String rawMessage,
                          @Header(name = TraceIds.KAFKA_HEADER, required = false) String headerTraceId) {
        JsonNode envelope = readEnvelope(rawMessage);
        try (TraceContext.Scope ignored = KafkaTraceSupport.open(
                envelope, headerTraceId, log, text(envelope, "eventId"))) {
            handleEnvelope(envelope);
        }
    }

    private void handleEnvelope(JsonNode envelope) {
        String eventType = text(envelope, "event");
        // 专用 topic 上出现别的事件类型只可能是误投；抛异常会让这条消息永久卡住分区，
        // 快照的时效性远比这一条无关消息重要，因此记日志跳过。
        if (!EVENT_ACCOUNT_CONTACTS_REPORTED.equals(eventType)) {
            log.warn("通讯录快照 Topic 收到无关事件类型 eventType={} eventId={}",
                    eventType, text(envelope, "eventId"));
            return;
        }
        AccountContactsReportedEvent event = toContactsReportedEvent(envelope);
        log.info("协议账号通讯录快照分片收到 eventId={} tenantId={} accountId={} protocolAccountId={} "
                        + "snapshotId={} chunkSeq={}/{} totalCount={} chunkSize={} snapshotComplete={}",
                event.eventId(), event.tenantId(), event.accountId(), event.protocolAccountId(),
                event.snapshotId(), event.chunkSeq(), event.chunkCount(), event.totalCount(),
                event.contacts().size(), event.snapshotComplete());
        sink.handle(event);
    }

    private AccountContactsReportedEvent toContactsReportedEvent(JsonNode envelope) {
        JsonNode data = dataNode(envelope);
        return new AccountContactsReportedEvent(
                text(envelope, "eventId"),
                requiredLong(data, "tenantId", "通讯录快照事件缺少 data.tenantId"),
                requiredLong(data, "accountId", "通讯录快照事件缺少 data.accountId"),
                requiredText(data, "protocolAccountId", "通讯录快照事件缺少 data.protocolAccountId"),
                requiredText(data, "snapshotId", "通讯录快照事件缺少 data.snapshotId"),
                requiredEpochMillis(data, "queryStartedAt"),
                requiredEpochMillis(data, "snapshotCutoff"),
                data.path("snapshotComplete").asBoolean(false),
                requiredInt(data, "chunkSeq", "通讯录快照事件缺少 data.chunkSeq"),
                requiredInt(data, "chunkCount", "通讯录快照事件缺少 data.chunkCount"),
                requiredInt(data, "totalCount", "通讯录快照事件缺少 data.totalCount"),
                contacts(data.path("contacts")));
    }

    private static List<AccountContactsReportedEvent.ReportedContact> contacts(JsonNode contactsNode) {
        // 缺 contacts 与空数组是两回事：前者是坏消息，后者是「一个联系人都没有」的事实。
        if (!contactsNode.isArray()) {
            throw new BusinessException(ErrorCode.VALIDATION, "通讯录快照事件缺少 data.contacts");
        }
        List<AccountContactsReportedEvent.ReportedContact> contacts =
                new ArrayList<>(contactsNode.size());
        for (JsonNode node : contactsNode) {
            contacts.add(new AccountContactsReportedEvent.ReportedContact(
                    requiredText(node, "phone", "通讯录快照事件缺少 contacts[].phone"),
                    requiredText(node, "jid", "通讯录快照事件缺少 contacts[].jid"),
                    text(node, "fullName"),
                    text(node, "firstName"),
                    text(node, "pushName"),
                    text(node, "businessName")));
        }
        return contacts;
    }

    private JsonNode readEnvelope(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "通讯录快照事件消息为空");
        }
        try {
            return objectMapper.readTree(rawMessage);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "通讯录快照事件 JSON 解析失败");
        }
    }

    private static JsonNode dataNode(JsonNode envelope) {
        return envelope.path("data").isObject() ? envelope.path("data") : envelope;
    }

    /** wire 上是 ISO8601，落库要 epoch 毫秒；转不出来即判非法，交 Kafka 重投。 */
    private static long requiredEpochMillis(JsonNode node, String fieldName) {
        String raw = text(node, fieldName);
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION, "通讯录快照事件缺少 data." + fieldName);
        }
        try {
            return Instant.parse(raw).toEpochMilli();
        } catch (DateTimeParseException ex) {
            throw new BusinessException(
                    ErrorCode.VALIDATION, "通讯录快照事件 data." + fieldName + " 格式非法");
        }
    }

    private static Long requiredLong(JsonNode node, String fieldName, String errorMessage) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull() || !value.canConvertToLong()) {
            throw new BusinessException(ErrorCode.VALIDATION, errorMessage);
        }
        return value.asLong();
    }

    private static int requiredInt(JsonNode node, String fieldName, String errorMessage) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull() || !value.canConvertToInt()) {
            throw new BusinessException(ErrorCode.VALIDATION, errorMessage);
        }
        return value.asInt();
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
