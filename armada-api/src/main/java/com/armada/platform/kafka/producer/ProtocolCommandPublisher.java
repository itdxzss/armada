package com.armada.platform.kafka.producer;

import com.armada.account.mapper.AccountCredentialMapper;
import com.armada.account.model.entity.AccountCredential;
import com.armada.platform.kafka.config.ProtocolCommandPublisherProperties;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.protocol.model.command.ProtocolCommandEnvelope;
import com.armada.platform.protocol.model.command.ProxyDescriptor;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.result.ProtocolCommandPublishOutcome;
import com.armada.platform.protocol.model.result.ProtocolCommandPublishResult;
import com.armada.platform.proxy.ProxyCredentials;
import com.armada.platform.proxy.ProxyEndpoint;
import com.armada.platform.proxy.ProxyResolver;
import com.armada.resource.mapper.IpProxyMapper;
import com.armada.resource.model.IpProxyStatus;
import com.armada.resource.model.entity.IpProxy;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
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
    private static final TypeReference<Map<String, Object>> CREDENTIAL_TYPE = new TypeReference<>() {
    };

    private final KafkaTemplate<String, ProtocolCommandEnvelope> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ProtocolCommandPublisherProperties properties;
    private final AccountCredentialMapper credentialMapper;
    private final IpProxyMapper ipProxyMapper;
    private final ProxyResolver proxyResolver;

    /**
     * 创建协议命令 Kafka publisher。
     *
     * @param kafkaTemplate KafkaTemplate
     * @param objectMapper JSON 解析器
     * @param properties publisher 配置
     */
    public ProtocolCommandPublisher(KafkaTemplate<String, ProtocolCommandEnvelope> kafkaTemplate,
                                    ObjectMapper objectMapper,
                                    ProtocolCommandPublisherProperties properties,
                                    AccountCredentialMapper credentialMapper,
                                    IpProxyMapper ipProxyMapper,
                                    ProxyResolver proxyResolver) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.credentialMapper = credentialMapper;
        this.ipProxyMapper = ipProxyMapper;
        this.proxyResolver = proxyResolver;
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
     * 每条命令仍独立返回结果,dispatcher 可以继续逐行标记 SENT/RETRY/DEAD。</p>
     *
     * @param rows 已锁定的 outbox 行
     * @return 与 rows 顺序一致的发送结果
     */
    public List<ProtocolCommandPublishOutcome> publishBatch(List<ProtocolCommandOutbox> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        PreparedEnvelopes prepared = prepareEnvelopes(rows);
        List<ProtocolCommandPublishOutcome> outcomes = new ArrayList<>(rows.size());
        for (ProtocolCommandOutbox row : rows) {
            RuntimeException prepareFailure = prepared.failures().get(commandKey(row));
            if (prepareFailure != null) {
                outcomes.add(ProtocolCommandPublishOutcome.failure(row, prepareFailure));
                continue;
            }
            ProtocolCommandEnvelope envelope = prepared.envelopes().get(commandKey(row));
            if (envelope == null) {
                outcomes.add(ProtocolCommandPublishOutcome.failure(row,
                        validation("协议命令 envelope 缺失: " + safeCommandId(row))));
                continue;
            }
            try {
                outcomes.add(ProtocolCommandPublishOutcome.success(row, send(row, envelope)));
            } catch (RuntimeException ex) {
                outcomes.add(ProtocolCommandPublishOutcome.failure(row, ex));
            }
        }
        return outcomes;
    }

    private ProtocolCommandPublishResult send(ProtocolCommandOutbox row, ProtocolCommandEnvelope envelope) {
        try {
            kafkaTemplate.send(row.getKafkaTopic(), row.getKafkaKey(), envelope)
                    .get(properties.getSendTimeoutMs(), TimeUnit.MILLISECONDS);
            log.debug("协议命令 Kafka 发送成功 commandId={} batchId={} accountId={} protocolAccountId={} topic={}",
                    row.getCommandId(), row.getBatchId(), row.getAggregateId(), row.getProtocolAccountId(),
                    row.getKafkaTopic());
            return new ProtocolCommandPublishResult(row.getCommandId(), row.getKafkaTopic(), row.getKafkaKey());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw kafkaFailure(row, ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw kafkaFailure(row, ex);
        }
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
                } else {
                    envelopes.put(commandKey(row), toEnvelope(row, payload(row)));
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
        try {
            for (AccountCredential credential : credentialMapper.selectByTenantAndAccountIds(tenantId, accountIds)) {
                credentialsByAccountId.put(credential.getAccountId(), credential);
            }
            for (IpProxy proxy : ipProxyMapper.selectActiveByTenantAndIds(tenantId, proxyIds)) {
                proxiesById.put(proxy.getId(), proxy);
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
                                ref.source()))));
            } catch (RuntimeException ex) {
                failures.put(commandKey(ref.row()), ex);
            }
        }
    }

    private OnlineRowRef onlineRef(ProtocolCommandOutbox row, JsonNode payload) {
        Long tenantId = row.getTenantId();
        if (tenantId == null) {
            throw validation("协议上线命令缺少租户 ID commandId=" + row.getCommandId());
        }
        Long accountId = requiredLong(payload, "accountId", row.getCommandId());
        Long proxyId = requiredLong(payload, "proxyId", row.getCommandId());
        String protocolAccountId = textOrDefault(payload, "protocolAccountId", row.getProtocolAccountId());
        String source = textOrDefault(payload, "source", "unknown");
        CredentialFormat format = credentialFormat(requiredText(payload, "credentialFormat", row.getCommandId()));
        return new OnlineRowRef(row, tenantId, accountId, protocolAccountId, format, proxyId, source);
    }

    private ProtocolCommandEnvelope toEnvelope(ProtocolCommandOutbox row) {
        return toEnvelope(row, payload(row));
    }

    private ProtocolCommandEnvelope toEnvelope(ProtocolCommandOutbox row, JsonNode payload) {
        return new ProtocolCommandEnvelope(
                row.getCommandId(),
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
        if (value == null || value.asText().isBlank()) {
            throw validation("协议上线命令 payload 缺少字段 " + field + " commandId=" + commandId);
        }
        return value.asText();
    }

    private static String textOrDefault(JsonNode payload, String field, String defaultValue) {
        JsonNode value = payload.get(field);
        if (value == null || value.asText().isBlank()) {
            return defaultValue;
        }
        return value.asText();
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

    private ProtocolException kafkaFailure(ProtocolCommandOutbox row, Exception ex) {
        return ProtocolException.unknown("协议命令 Kafka 发送失败 commandId=" + row.getCommandId(), ex);
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
            String source
    ) {
    }

    private record OnlineCommandKafkaPayload(
            Long tenantId,
            Long accountId,
            String protocolAccountId,
            String format,
            Map<String, Object> credential,
            ProxyDescriptor proxy,
            String source
    ) {
    }
}
