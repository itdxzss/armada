package com.armada.platform.kafka.producer;

import com.armada.account.mapper.AccountCredentialMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.AccountCredential;
import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.account.model.vo.AccountGroupBaselineStateRow;
import com.armada.platform.kafka.config.ProtocolCommandPublisherProperties;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.protocol.model.command.ProtocolCommandEnvelope;
import com.armada.platform.protocol.model.command.ProxyDescriptor;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.result.ProtocolCommandPublishOutcome;
import com.armada.platform.protocol.model.result.ProtocolCommandPublishResult;
import com.armada.platform.protocol.service.ProtocolCommandPayloadHydrator;
import com.armada.platform.proxy.ProxyCredentials;
import com.armada.platform.proxy.ProxyEndpoint;
import com.armada.platform.proxy.ProxyResolver;
import com.armada.resource.mapper.IpProxyMapper;
import com.armada.resource.model.IpProxyStatus;
import com.armada.resource.model.entity.IpProxy;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.trace.TraceContext;
import com.armada.shared.trace.TraceIds;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.stereotype.Service;

/**
 * 协议命令 Kafka publisher。
 *
 * <p>本类只负责把已存在的 outbox row 转换为 Kafka envelope 并发送。它不扫描 outbox,
 * 不修改 outbox 状态,也不接入账号上线入口。</p>
 */
@Service
public class ProtocolCommandPublisher {

    private static final Logger log = LoggerFactory.getLogger(ProtocolCommandPublisher.class);
    private static final String COMMAND_TYPE_ACCOUNT_ONLINE_REQUESTED = "account.online.requested";
    private static final String COMMAND_TYPE_ACCOUNT_OFFLINE_REQUESTED = "account.offline.requested";
    private static final long DEFAULT_PRODUCER_MAX_BLOCK_MS = 60_000L;
    private static final TypeReference<Map<String, Object>> CREDENTIAL_TYPE = new TypeReference<>() {
    };

    private final KafkaTemplate<String, ProtocolCommandEnvelope> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ProtocolCommandPublisherProperties properties;
    private final AccountCredentialMapper credentialMapper;
    private final AccountMapper accountMapper;
    private final IpProxyMapper ipProxyMapper;
    private final ProxyResolver proxyResolver;
    private final List<ProtocolCommandPayloadHydrator> payloadHydrators;
    private final long maximumWindowSendDurationMs;

    /**
     * 创建协议命令 Kafka publisher。
     *
     * @param kafkaTemplate KafkaTemplate
     * @param objectMapper JSON 解析器
     * @param properties publisher 配置
     * @param credentialMapper 账号凭据 Mapper
     * @param ipProxyMapper IP 代理 Mapper
     * @param proxyResolver 代理连接参数解析器
     * @param payloadHydrators 业务引用 payload 补全器
     */
    public ProtocolCommandPublisher(KafkaTemplate<String, ProtocolCommandEnvelope> kafkaTemplate,
                                    ObjectMapper objectMapper,
                                    ProtocolCommandPublisherProperties properties,
                                    AccountCredentialMapper credentialMapper,
                                    AccountMapper accountMapper,
                                    IpProxyMapper ipProxyMapper,
                                    ProxyResolver proxyResolver,
                                    List<ProtocolCommandPayloadHydrator> payloadHydrators) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.credentialMapper = credentialMapper;
        this.accountMapper = accountMapper;
        this.ipProxyMapper = ipProxyMapper;
        this.proxyResolver = proxyResolver;
        this.payloadHydrators = List.copyOf(payloadHydrators);
        this.maximumWindowSendDurationMs = resolveMaximumWindowSendDurationMs(
                kafkaTemplate, properties);
    }

    /**
     * 获取单个 Kafka 发送窗口从首条同步提交到全部 ACK 收敛的保守时间上界。
     *
     * <p>{@link KafkaTemplate#send(String, Object, Object)} 在返回 Future 前可能为元数据或 producer
     * buffer 同步阻塞到 {@code max.block.ms}。一个窗口会依次提交最多 max-in-flight 条记录，因此
     * 上界为 {@code maxInFlight * max.block.ms + sendTimeoutMs}，并采用饱和运算避免配置溢出。
     * Dispatcher 用该值避免 recovery 把仍在提交或等待 ACK 的命令提前判为结果未知。</p>
     *
     * @return 单窗口发送的保守时间上界，单位毫秒
     */
    public long maximumWindowSendDurationMs() {
        return maximumWindowSendDurationMs;
    }

    /**
     * 发送单条协议命令。
     *
     * @param row 待发送 outbox 行
     * @return 发送结果,供后续 scheduler 标记 SENT 使用
     * @throws BusinessException outbox 行缺少必要字段或 payload JSON 非法时抛出
     * @throws ProtocolException Kafka send 失败或超时时抛出,供后续 scheduler 标记 retry/dead
     */
    public ProtocolCommandPublishResult publish(ProtocolCommandOutbox row) {
        List<ProtocolCommandPublishOutcome> outcomes = publishBatch(List.of(row));
        ProtocolCommandPublishOutcome outcome = outcomes.get(0);
        if (!outcome.succeeded()) {
            throw outcome.error();
        }
        return outcome.result();
    }

    /**
     * 批量发送协议命令。
     *
     * <p>online 命令在本方法开头按 tenant_id 分组批量读取凭据和代理,避免一条命令一次查库。
     * 命令按配置的最大在途数分窗口异步发送,最终结果保持输入顺序。</p>
     *
     * @param rows 已锁定的 outbox 行
     * @return 与 rows 顺序一致的发送结果
     */
    public List<ProtocolCommandPublishOutcome> publishBatch(List<ProtocolCommandOutbox> rows) {
        List<ProtocolCommandPublishOutcome> outcomes = new ArrayList<>();
        publishBatchByWindow(rows, outcomes::addAll);
        return List.copyOf(outcomes);
    }

    /**
     * 按有界窗口批量发送协议命令，并在每个窗口结果收敛后同步通知调用方。
     *
     * <p>凭据和代理在整批开始时只准备一次。窗口消费者返回后才会提交下一窗口，因此 Dispatcher
     * 可以在不增加 Kafka 在途数的前提下及时批量回写 SENT；窗口消费者抛出异常时停止后续发送，
     * 已获得 ACK 但未回写的行继续沿用 Outbox 至少一次投递语义。</p>
     *
     * @param rows 已锁定的 outbox 行
     * @param completedWindowConsumer 已完成窗口的结果消费者，结果顺序与该窗口输入顺序一致
     */
    public void publishBatchByWindow(
            List<ProtocolCommandOutbox> rows,
            Consumer<List<ProtocolCommandPublishOutcome>> completedWindowConsumer) {
        publishBatchByWindow(rows, Function.identity(), completedWindowConsumer);
    }

    /**
     * 按有界窗口发送，并在每个窗口真正提交 Kafka 前由调用方做最后状态 CAS。
     *
     * @param rows 已预抢占的 outbox 行
     * @param dispatchingWindowSelector 返回本窗口中实际获得发送权的行
     * @param completedWindowConsumer 已完成窗口的结果消费者
     */
    public void publishBatchByWindow(
            List<ProtocolCommandOutbox> rows,
            Function<List<ProtocolCommandOutbox>, List<ProtocolCommandOutbox>> dispatchingWindowSelector,
            Consumer<List<ProtocolCommandPublishOutcome>> completedWindowConsumer) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Objects.requireNonNull(dispatchingWindowSelector, "dispatchingWindowSelector");
        Objects.requireNonNull(completedWindowConsumer, "completedWindowConsumer");
        PreparedEnvelopes prepared = prepareEnvelopes(rows);
        int maxInFlight = properties.getMaxInFlight();
        for (int start = 0; start < rows.size(); start += maxInFlight) {
            int end = Math.min(start + maxInFlight, rows.size());
            List<ProtocolCommandOutbox> dispatchingRows = Objects.requireNonNull(
                    dispatchingWindowSelector.apply(List.copyOf(rows.subList(start, end))),
                    "dispatchingWindowSelector result");
            if (!dispatchingRows.isEmpty()) {
                completedWindowConsumer.accept(publishWindow(dispatchingRows, prepared));
            }
        }
    }

    /**
     * 提交一个有界发送窗口并等待窗口内全部 Kafka 结果收敛。
     *
     * <p>第一个循环只调用异步 send，不等待单条 ACK，因此 Kafka Producer 可以把同 Topic/Partition 的 Record
     * 自动组成 RecordBatch。准备失败也转换为已完成 Future，保证返回结果仍与窗口输入顺序一致。</p>
     *
     * @param rows 当前发送窗口
     * @param prepared 整批已准备的 envelope 和准备失败结果
     * @return 与当前窗口输入顺序一致的发送结果
     */
    private List<ProtocolCommandPublishOutcome> publishWindow(
            List<ProtocolCommandOutbox> rows,
            PreparedEnvelopes prepared) {
        List<CompletableFuture<ProtocolCommandPublishOutcome>> pending = new ArrayList<>(rows.size());
        for (ProtocolCommandOutbox row : rows) {
            RuntimeException prepareFailure = prepared.failures().get(commandKey(row));
            if (prepareFailure != null) {
                pending.add(CompletableFuture.completedFuture(
                        ProtocolCommandPublishOutcome.failure(row, prepareFailure)));
                continue;
            }
            ProtocolCommandEnvelope envelope = prepared.envelopes().get(commandKey(row));
            if (envelope == null) {
                pending.add(CompletableFuture.completedFuture(
                        ProtocolCommandPublishOutcome.failure(row,
                                validation("协议命令 envelope 缺失: " + safeCommandId(row)))));
                continue;
            }
            pending.add(sendAsync(row, envelope));
        }
        // sendAsync 已把单条成功和失败都转换为正常完成的 outcome，单条失败不会让 allOf 提前打断整窗。
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
        return pending.stream().map(CompletableFuture::join).toList();
    }

    /**
     * 异步提交单条 Kafka Record，并把 producer ACK 或异常转换为该 outbox 行的独立结果。
     *
     * <p>超时从调用 send 时开始计时，避免窗口内较晚读取 Future 的消息获得额外等待时间。异常在 Future 内部
     * 收敛为失败 outcome，使其它消息可以继续独立完成。</p>
     *
     * @param row 待发送 outbox 行
     * @param envelope Kafka 命令 envelope
     * @return 最终正常完成并携带单条发送结果的 Future
     */
    private CompletableFuture<ProtocolCommandPublishOutcome> sendAsync(
            ProtocolCommandOutbox row,
            ProtocolCommandEnvelope envelope) {
        try {
            ProducerRecord<String, ProtocolCommandEnvelope> record = new ProducerRecord<>(
                    row.getKafkaTopic(), row.getKafkaKey(), envelope);
            record.headers().add(new RecordHeader(
                    TraceIds.KAFKA_HEADER,
                    envelope.traceId().getBytes(StandardCharsets.UTF_8)));
            return kafkaTemplate.send(record)
                    .orTimeout(properties.getSendTimeoutMs(), TimeUnit.MILLISECONDS)
                    .handle((ignored, error) -> {
                        try (TraceContext.Scope scope = TraceContext.open(envelope.traceId())) {
                            if (error != null) {
                                return ProtocolCommandPublishOutcome.failure(
                                        row, kafkaFailure(row, unwrapCompletion(error)));
                            }
                            log.debug("协议命令 Kafka 发送成功 commandId={} batchId={} accountId={} "
                                            + "protocolAccountId={} topic={} traceId={}",
                                    row.getCommandId(), row.getBatchId(), row.getAggregateId(),
                                    row.getProtocolAccountId(), row.getKafkaTopic(), envelope.traceId());
                            return ProtocolCommandPublishOutcome.success(row,
                                    new ProtocolCommandPublishResult(
                                            row.getCommandId(), row.getKafkaTopic(), row.getKafkaKey()));
                        }
                    });
        } catch (RuntimeException ex) {
            // serializer、metadata 或 producer 缓冲区可能在返回 Future 前同步抛错，也必须保持逐行失败语义。
            return CompletableFuture.completedFuture(
                    ProtocolCommandPublishOutcome.failure(row, kafkaFailure(row, ex)));
        }
    }

    private static long resolveMaximumWindowSendDurationMs(
            KafkaTemplate<String, ProtocolCommandEnvelope> kafkaTemplate,
            ProtocolCommandPublisherProperties properties) {
        long maxBlockMs = resolveProducerMaxBlockMs(kafkaTemplate);
        long maxInFlight = Math.max(1L, properties.getMaxInFlight());
        long sendTimeoutMs = properties.getSendTimeoutMs() > 0
                ? properties.getSendTimeoutMs()
                : ProtocolCommandPublisherProperties.DEFAULT_SEND_TIMEOUT_MS;
        return saturatingAdd(saturatingMultiply(maxBlockMs, maxInFlight), sendTimeoutMs);
    }

    private static long resolveProducerMaxBlockMs(
            KafkaTemplate<String, ProtocolCommandEnvelope> kafkaTemplate) {
        ProducerFactory<String, ProtocolCommandEnvelope> producerFactory =
                kafkaTemplate.getProducerFactory();
        if (producerFactory == null) {
            return DEFAULT_PRODUCER_MAX_BLOCK_MS;
        }
        Object configured = producerFactory.getConfigurationProperties()
                .get(ProducerConfig.MAX_BLOCK_MS_CONFIG);
        if (configured instanceof Number number) {
            return number.longValue() > 0 ? number.longValue() : DEFAULT_PRODUCER_MAX_BLOCK_MS;
        }
        if (configured != null) {
            try {
                long parsed = Long.parseLong(configured.toString());
                return parsed > 0 ? parsed : DEFAULT_PRODUCER_MAX_BLOCK_MS;
            } catch (NumberFormatException ignored) {
                // 非法值最终会由 Kafka 自身配置校验拒绝；这里用默认值保持 recovery 上界保守。
            }
        }
        return DEFAULT_PRODUCER_MAX_BLOCK_MS;
    }

    private static long saturatingMultiply(long left, long right) {
        if (left > 0 && right > Long.MAX_VALUE / left) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static long saturatingAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    /**
     * 提取 CompletableFuture 包装的真实异常，避免重试日志只看到 CompletionException 外壳。
     */
    private static Throwable unwrapCompletion(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private PreparedEnvelopes prepareEnvelopes(List<ProtocolCommandOutbox> rows) {
        Map<String, ProtocolCommandEnvelope> envelopes = new LinkedHashMap<>();
        Map<String, RuntimeException> failures = new LinkedHashMap<>();
        Map<Long, List<OnlineRowRef>> onlineByTenant = new LinkedHashMap<>();

        for (ProtocolCommandOutbox row : rows) {
            try {
                validateRow(row);
                if (COMMAND_TYPE_ACCOUNT_ONLINE_REQUESTED.equals(row.getCommandType())) {
                    OnlineRowRef ref = onlineRef(row, payload(row));
                    onlineByTenant.computeIfAbsent(ref.tenantId(), ignored -> new ArrayList<>()).add(ref);
                } else if (COMMAND_TYPE_ACCOUNT_OFFLINE_REQUESTED.equals(row.getCommandType())) {
                    envelopes.put(commandKey(row), toOfflineEnvelope(row, payload(row)));
                } else {
                    JsonNode referencePayload = payload(row);
                    envelopes.put(commandKey(row), toEnvelope(
                            row, hydratePayload(row, referencePayload)));
                }
            } catch (RuntimeException ex) {
                failures.put(commandKey(row), ex);
            }
        }

        for (Map.Entry<Long, List<OnlineRowRef>> entry : onlineByTenant.entrySet()) {
            hydrateOnlineRows(entry.getKey(), entry.getValue(), envelopes, failures);
        }
        return new PreparedEnvelopes(envelopes, failures);
    }

    /** 选择唯一业务补全器；没有匹配时沿用既有直通 payload。 */
    private JsonNode hydratePayload(ProtocolCommandOutbox row, JsonNode referencePayload) {
        List<ProtocolCommandPayloadHydrator> matching = payloadHydrators.stream()
                .filter(hydrator -> hydrator.supports(row))
                .toList();
        if (matching.size() > 1) {
            throw validation("协议命令匹配到多个 payload 补全器 commandId=" + row.getCommandId());
        }
        return matching.isEmpty()
                ? referencePayload
                : matching.get(0).hydrate(row, referencePayload);
    }

    private void hydrateOnlineRows(Long tenantId,
                                   List<OnlineRowRef> refs,
                                   Map<String, ProtocolCommandEnvelope> envelopes,
                                   Map<String, RuntimeException> failures) {
        List<Long> accountIds = refs.stream()
                .map(OnlineRowRef::accountId)
                .distinct()
                .toList();
        List<Long> proxyIds = refs.stream()
                .map(OnlineRowRef::proxyId)
                .distinct()
                .toList();
        Map<Long, AccountCredential> credentialsByAccountId = new LinkedHashMap<>();
        Map<Long, IpProxy> proxiesById = new LinkedHashMap<>();
        Map<Long, Boolean> baselineReadyByAccountId = new LinkedHashMap<>();
        try {
            for (AccountCredential credential : credentialMapper.selectByTenantAndAccountIds(tenantId, accountIds)) {
                credentialsByAccountId.put(credential.getAccountId(), credential);
            }
            for (IpProxy proxy : ipProxyMapper.selectActiveByTenantAndIds(tenantId, proxyIds)) {
                proxiesById.put(proxy.getId(), proxy);
            }
            for (AccountGroupBaselineStateRow row
                    : accountMapper.selectGroupBaselineStatesByTenantAndAccountIds(tenantId, accountIds)) {
                if (row.accountId() != null) {
                    baselineReadyByAccountId.put(row.accountId(), groupBaselineReady(row.groupBaselineState()));
                }
            }
        } catch (RuntimeException ex) {
            for (OnlineRowRef ref : refs) {
                failures.put(commandKey(ref.row()), ex);
            }
            return;
        }
        for (OnlineRowRef ref : refs) {
            try {
                AccountCredential credential = credentialsByAccountId.get(ref.accountId());
                if (credential == null) {
                    throw validation("协议上线命令缺少账号凭据 accountId=" + ref.accountId());
                }
                IpProxy proxy = proxiesById.get(ref.proxyId());
                if (proxy == null) {
                    throw validation("协议上线命令缺少代理 proxyId=" + ref.proxyId());
                }
                validateProxyBinding(ref, proxy);
                Map<String, Object> credentialPayload = parseCredential(credential.getCredsJson(), ref.format());
                ProxyDescriptor proxyPayload = proxyResolver.resolve(toEndpoint(proxy));
                envelopes.put(commandKey(ref.row()), toEnvelope(ref.row(), objectMapper.valueToTree(
                        new OnlineCommandKafkaPayload(
                                ref.tenantId(),
                                ref.accountId(),
                                ref.protocolAccountId(),
                                toWireFormat(ref.format()),
                                credentialPayload,
                                proxyPayload,
                                ref.isBusiness(),
                                ref.source(),
                                ref.onlineAttemptId(),
                                ref.previousOnlineAttemptId(),
                                ref.protocolBackend(),
                                baselineReadyByAccountId.getOrDefault(ref.accountId(), false)))));
            } catch (RuntimeException ex) {
                failures.put(commandKey(ref.row()), ex);
            }
        }
    }

    /**
     * 判定账号群基线是否已建立,决定协议层上线快照是否可以跳过群成员明细。
     *
     * <p>与 {@code selectGroupSyncCandidates} 保持同一约定:已拍(CAPTURED)与已停用(DISABLED)
     * 都视为基线已定,只有待拍(PENDING)以及历史空值需要读取明细一次性建立底数。</p>
     *
     * @param groupBaselineState 群基线状态码,可空
     * @return 基线已建立返回 true
     */
    private static boolean groupBaselineReady(Integer groupBaselineState) {
        return groupBaselineState != null
                && groupBaselineState != AccountGroupBaselineStateCode.PENDING;
    }

    private OnlineRowRef onlineRef(ProtocolCommandOutbox row, JsonNode payload) {
        Long tenantId = row.getTenantId();
        if (tenantId == null) {
            throw validation("协议上线命令缺少租户 ID commandId=" + row.getCommandId());
        }
        Long accountId = requiredLong(payload, "accountId", row.getCommandId());
        Long proxyId = requiredLong(payload, "proxyId", row.getCommandId());
        String protocolAccountId = textOrDefault(payload, "protocolAccountId", row.getProtocolAccountId());
        boolean isBusiness = booleanValue(payload, "isBusiness", false);
        String source = textOrDefault(payload, "source", "unknown");
        String onlineAttemptId = requiredText(payload, "onlineAttemptId", row.getCommandId());
        String previousOnlineAttemptId = textOrDefault(payload, "previousOnlineAttemptId", null);
        String protocolBackend = protocolBackend(payload, row);
        CredentialFormat format = credentialFormat(requiredText(payload, "credentialFormat", row.getCommandId()));
        return new OnlineRowRef(row, tenantId, accountId, protocolAccountId, format, proxyId, isBusiness, source,
                onlineAttemptId, previousOnlineAttemptId, protocolBackend);
    }

    private ProtocolCommandEnvelope toEnvelope(ProtocolCommandOutbox row) {
        return toEnvelope(row, payload(row));
    }

    private ProtocolCommandEnvelope toOfflineEnvelope(ProtocolCommandOutbox row, JsonNode payload) {
        Long tenantId = row.getTenantId();
        if (tenantId == null) {
            throw validation("协议下线命令缺少租户 ID commandId=" + row.getCommandId());
        }
        if (!payload.isObject()) {
            throw validation("协议下线命令 payload 必须是 JSON object commandId=" + row.getCommandId());
        }
        ObjectNode hydratedPayload = payload.deepCopy();
        hydratedPayload.put("tenantId", tenantId);
        return toEnvelope(row, hydratedPayload);
    }

    private ProtocolCommandEnvelope toEnvelope(ProtocolCommandOutbox row, JsonNode payload) {
        String traceId = TraceIds.normalize(row.getTraceId())
                .orElseGet(() -> TraceIds.stableFrom(row.getCommandId()));
        return new ProtocolCommandEnvelope(
                row.getCommandId(),
                traceId,
                row.getBatchId(),
                row.getCommandType(),
                row.getAggregateType(),
                row.getAggregateId(),
                row.getProtocolAccountId(),
                payload);
    }

    private JsonNode payload(ProtocolCommandOutbox row) {
        try {
            return objectMapper.readTree(row.getPayloadJson());
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "协议命令 payload JSON 非法: " + row.getCommandId());
        }
    }

    private Map<String, Object> parseCredential(String credentialJson, CredentialFormat format) {
        if (credentialJson == null || credentialJson.isBlank()) {
            throw validation("协议上线凭据为空 format=" + format);
        }
        try {
            return objectMapper.readValue(credentialJson, CREDENTIAL_TYPE);
        } catch (Exception ex) {
            throw validation("协议上线凭据不是合法 JSON object format=" + format
                    + " credentialLength=" + credentialJson.length());
        }
    }

    private void validateProxyBinding(OnlineRowRef ref, IpProxy proxy) {
        if (!Integer.valueOf(IpProxyStatus.IN_USE.code()).equals(proxy.getStatus())
                || !ref.accountId().equals(proxy.getBoundAccountId())) {
            throw validation("协议上线代理绑定已失效 accountId=" + ref.accountId() + " proxyId=" + ref.proxyId());
        }
    }

    private ProxyEndpoint toEndpoint(IpProxy proxy) {
        return new ProxyEndpoint(
                proxy.getProtocol(),
                proxy.getHost(),
                proxy.getPort(),
                new ProxyCredentials(proxy.getUsername(), proxy.getPassword()),
                proxy.getRegion());
    }

    private String toWireFormat(CredentialFormat format) {
        return switch (format) {
            case SIX_SEGMENT -> "six";
            case BAILEYS_JSON -> "baileys_json";
            case PARAMS -> "params";
        };
    }

    private CredentialFormat credentialFormat(String value) {
        try {
            return CredentialFormat.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw validation("协议上线凭据格式非法: " + value);
        }
    }

    private static Long requiredLong(JsonNode payload, String field, String commandId) {
        JsonNode value = payload.get(field);
        if (value == null || !value.canConvertToLong()) {
            throw validation("协议上线命令 payload 缺少字段 " + field + " commandId=" + commandId);
        }
        return value.asLong();
    }

    private static String requiredText(JsonNode payload, String field, String commandId) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw validation("协议上线命令 payload 缺少字段 " + field + " commandId=" + commandId);
        }
        return value.asText();
    }

    private static String textOrDefault(JsonNode payload, String field, String defaultValue) {
        JsonNode value = payload.get(field);
        // 可空链路字段只接受 JSON string；missing/null/blank 都保持调用方定义的默认值，避免 null 被转成字符串。
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return defaultValue;
        }
        return value.asText();
    }

    private static boolean booleanValue(JsonNode payload, String fieldName, boolean defaultValue) {
        JsonNode value = payload.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw validation("协议上线命令字段不是布尔值: " + fieldName);
        }
        return value.asBoolean();
    }

    private static String protocolBackend(JsonNode payload, ProtocolCommandOutbox row) {
        String payloadBackend = textOrDefault(payload, "protocolBackend", null);
        if (!isBlank(payloadBackend)) {
            return payloadBackend;
        }
        if (!isBlank(row.getProtocolBackend())) {
            return row.getProtocolBackend();
        }
        return "WEB";
    }

    private void validateRow(ProtocolCommandOutbox row) {
        if (row == null
                || isBlank(row.getCommandId())
                || isBlank(row.getCommandType())
                || isBlank(row.getAggregateType())
                || row.getAggregateId() == null
                || isBlank(row.getKafkaTopic())
                || isBlank(row.getKafkaKey())
                || isBlank(row.getProtocolAccountId())
                || isBlank(row.getPayloadJson())) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议命令 outbox 行缺少必要字段");
        }
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    private ProtocolException kafkaFailure(ProtocolCommandOutbox row, Throwable error) {
        return ProtocolException.unknown("协议命令 Kafka 发送失败 commandId=" + row.getCommandId(), error);
    }

    private static String commandKey(ProtocolCommandOutbox row) {
        return row == null ? "<null>" : safeCommandId(row);
    }

    private static String safeCommandId(ProtocolCommandOutbox row) {
        return row.getCommandId() == null ? "<missing>" : row.getCommandId();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record PreparedEnvelopes(
            Map<String, ProtocolCommandEnvelope> envelopes,
            Map<String, RuntimeException> failures
    ) {
    }

    private record OnlineRowRef(
            ProtocolCommandOutbox row,
            Long tenantId,
            Long accountId,
            String protocolAccountId,
            CredentialFormat format,
            Long proxyId,
            boolean isBusiness,
            String source,
            String onlineAttemptId,
            String previousOnlineAttemptId,
            String protocolBackend
    ) {
    }

    private record OnlineCommandKafkaPayload(
            Long tenantId,
            Long accountId,
            String protocolAccountId,
            String format,
            Map<String, Object> credential,
            ProxyDescriptor proxy,
            boolean isBusiness,
            String source,
            String onlineAttemptId,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            String previousOnlineAttemptId,
            String protocolBackend,
            /*
             * 群基线已建立时,协议层上线快照不再读取群成员明细,改由 <group> 的建群人属性判定角色。
             * 字段取"已就绪"语义:协议层收不到该字段时零值 false 退化为读取明细的旧行为,
             * 只多花流量,不会漏建基线。
             */
            @JsonInclude(JsonInclude.Include.ALWAYS)
            boolean groupBaselineReady
    ) {
    }
}
