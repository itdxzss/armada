package com.armada.platform.protocol.http.account;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.BatchOnlineCommand;
import com.armada.platform.protocol.model.command.BatchOnlineCommandItem;
import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.protocol.model.command.OnlineCommand;
import com.armada.platform.protocol.model.command.ProxyDescriptor;
import com.armada.platform.protocol.model.result.BatchOnlineAccepted;
import com.armada.platform.protocol.model.result.BatchOnlineItemResult;
import com.armada.platform.protocol.model.result.BatchOnlineRemoteRoute;
import com.armada.platform.protocol.model.result.BatchOnlineResultStatus;
import com.armada.platform.protocol.model.result.BatchOnlineSummary;
import com.armada.platform.protocol.model.result.OnlineAccepted;
import com.armada.platform.protocol.model.result.OnlineRouting;
import com.armada.platform.protocol.model.result.ProtocolAccountStatus;
import com.armada.platform.protocol.model.result.ProtocolProbeResult;
import com.armada.platform.protocol.model.result.StateSource;
import com.armada.platform.protocol.port.AccountLifecyclePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@link AccountLifecyclePort} 的 HTTP adapter。
 *
 * <p>本类是 account lifecycle port 到协议层 HTTP wire 契约的翻译边界:业务侧只看
 * {@link OnlineCommand} / {@link OnlineAccepted},协议层 URL、format 字符串、JSON body 形状都收口在这里。
 * 上层服务不直接拼 HTTP body,这样协议层字段变化时只需要改这个 adapter。</p>
 *
 * <p>注意:online 和 batch online 的 HTTP 返回都只代表"协议层收到并处理了投递请求"。
 * 它不能说明 WhatsApp 账号已经真正 ONLINE,最终状态仍然后续由协议层异步回填 Kafka。</p>
 */
public class HttpAccountLifecycleAdapter implements AccountLifecyclePort {

    /** 单账号上线协议路径:/v1/accounts/{protocolAccountId}/online。 */
    private static final String ONLINE_URI_PREFIX = "/v1/accounts/";
    private static final String ONLINE_URI_SUFFIX = "/online";

    /** 批量上线协议路径:一次 HTTP 带多个账号,协议层内部排队/限速处理。 */
    private static final String BATCH_ONLINE_URI = "/v1/accounts/online/batch";

    /** 账号状态查询协议路径后缀。 */
    private static final String STATUS_URI_SUFFIX = "/status";

    /** 账号主动探活协议路径后缀。 */
    private static final String PROBE_URI_SUFFIX = "/probe";

    /** 协议层 wire format 字符串,不要把 Java enum 名直接暴露到 HTTP 契约里。 */
    private static final String WIRE_FORMAT_SIX = "six";
    private static final String WIRE_FORMAT_BAILEYS_JSON = "baileys_json";
    private static final String WIRE_FORMAT_PARAMS = "params";

    /** 凭据原文必须是 JSON object,这里解析成 Map 后作为 credential 字段透传给协议层。 */
    private static final TypeReference<Map<String, Object>> CREDENTIAL_TYPE = new TypeReference<>() {
    };

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProtocolHttpExecutor httpExecutor;

    /**
     * 创建账号生命周期 HTTP adapter。
     *
     * @param httpExecutor 协议层 HTTP 执行器
     */
    public HttpAccountLifecycleAdapter(ProtocolHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    /**
     * 调协议层发起账号上线 load+connect。
     *
     * @param protocolAccountId 协议层账号句柄
     * @param command           上线命令
     * @return 协议层上线受理回执
     * @throws ProtocolException 当凭据 JSON 无法解析、协议层返回错误或网络失败时抛出
     */
    @Override
    public OnlineAccepted online(String protocolAccountId, OnlineCommand command) {
        // 先校验并归一化协议账号 ID/命令对象,避免把空命令投递到协议层。
        String accountId = requireText(protocolAccountId, "protocolAccountId");
        OnlineCommand safeCommand = requireCommand(command);

        // 单账号协议 body:
        // {
        //   format: "baileys_json" | "params" | "six",
        //   credential: <解析后的 JSON object>,
        //   proxy: <ProxyResolver 已生成的协议代理描述>
        // }
        OnlineRequest request = new OnlineRequest(
                toWireFormat(safeCommand.format()),
                parseCredential(safeCommand.credentialJson(), safeCommand.format()),
                safeCommand.proxy());

        // ProtocolHttpExecutor 统一处理 baseUrl、HTTP 错误和 JSON 反序列化;本类只关心契约映射。
        OnlineResponse response = httpExecutor.postTyped(onlineUri(accountId), request, OnlineResponse.class);
        return toAccepted(response);
    }

    /**
     * 批量上线 HTTP 适配入口。
     *
     * <p>批量接口的 wire 契约与单账号 online 保持一致:每个 item 都带协议层账号 ID、
     * 凭据格式、解析后的 credential object 和代理描述。HTTP 返回只表示命令投递结果,
     * 不代表账号已经 ONLINE。</p>
     */
    @Override
    public BatchOnlineAccepted onlineBatch(BatchOnlineCommand command) {
        BatchOnlineCommand safeCommand = requireBatchCommand(command);

        // 批量请求仍然复用单账号 wire 语义,只是外层多了一层 items + maxWaitMs。
        // maxWaitMs 是协议层等待本批命令投递/令牌的最长时间,不是等待账号最终 ONLINE。
        BatchOnlineRequest request = toBatchRequest(safeCommand);
        BatchOnlineResponse response = httpExecutor.postTyped(BATCH_ONLINE_URI, request, BatchOnlineResponse.class);
        return toBatchAccepted(response);
    }

    /**
     * 查询协议层账号状态快照。
     *
     * @param protocolAccountId 协议层账号句柄
     * @return 协议层状态快照
     */
    @Override
    public ProtocolAccountStatus status(String protocolAccountId) {
        String accountId = requireText(protocolAccountId, "protocolAccountId");
        StatusResponse response = httpExecutor.getTyped(accountUri(accountId, STATUS_URI_SUFFIX), StatusResponse.class);
        return toProtocolAccountStatus(response);
    }

    /**
     * 主动探活账号。
     *
     * @param protocolAccountId 协议层账号句柄
     * @return 探活结果
     */
    @Override
    public ProtocolProbeResult probe(String protocolAccountId) {
        String accountId = requireText(protocolAccountId, "protocolAccountId");
        ProbeResponse response = httpExecutor.postTyped(accountUri(accountId, PROBE_URI_SUFFIX), null, ProbeResponse.class);
        return toProtocolProbeResult(response);
    }

    private static String onlineUri(String protocolAccountId) {
        return ONLINE_URI_PREFIX + protocolAccountId + ONLINE_URI_SUFFIX;
    }

    private static String accountUri(String protocolAccountId, String suffix) {
        return ONLINE_URI_PREFIX + protocolAccountId + suffix;
    }

    private static OnlineCommand requireCommand(OnlineCommand command) {
        if (command == null) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层上线命令缺失");
        }
        return command;
    }

    private static BatchOnlineCommand requireBatchCommand(BatchOnlineCommand command) {
        // 这里不限制 500,因为数量上限属于 armada account API 入口职责;adapter 只校验 HTTP 必填字段。
        if (command == null || command.items() == null || command.items().isEmpty()) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层批量上线命令为空");
        }
        if (command.maxWaitMs() <= 0) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层批量上线 maxWaitMs 必须大于 0");
        }
        return command;
    }

    private static String toWireFormat(CredentialFormat format) {
        if (format == null) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层上线凭据格式缺失");
        }
        // 协议层历史接口使用小写字符串;这里集中转换,避免业务层散落 hardcode。
        return switch (format) {
            case SIX_SEGMENT -> WIRE_FORMAT_SIX;
            case BAILEYS_JSON -> WIRE_FORMAT_BAILEYS_JSON;
            case PARAMS -> WIRE_FORMAT_PARAMS;
        };
    }

    private static Map<String, Object> parseCredential(String credentialJson, CredentialFormat format) {
        if (credentialJson == null || credentialJson.isBlank()) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN,
                    "协议层上线凭据为空 format=" + format);
        }
        try {
            // 协议层希望拿到 credential object,不是字符串;同时这里能提前发现坏 JSON。
            // 异常消息只带长度和格式,不打印完整凭据,避免日志泄露账号私密数据。
            return MAPPER.readValue(credentialJson, CREDENTIAL_TYPE);
        } catch (Exception ex) {
            throw new ProtocolException(
                    ProtocolErrorCode.UNKNOWN,
                    "协议层上线凭据不是合法 JSON object format=" + format
                            + " credentialLength=" + credentialJson.length(),
                    ex);
        }
    }

    private static OnlineAccepted toAccepted(OnlineResponse response) {
        if (response == null) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 online 响应为空");
        }
        // accepted=true 只代表协议层接收了上线命令;账号在线状态由后续 Kafka 状态回填决定。
        return new OnlineAccepted(
                requireText(response.accountId(), "accountId"),
                response.accepted(),
                toStateSource(response.stateSource()),
                requireSyncedAt(response.syncedAt()),
                toRouting(response.routing()));
    }

    private static StateSource toStateSource(String stateSource) {
        if (stateSource == null || stateSource.isBlank()) {
            return StateSource.UNKNOWN;
        }
        try {
            return StateSource.valueOf(stateSource);
        } catch (IllegalArgumentException ignored) {
            // 协议层可能先增加新枚举;armada 不应因为未知 stateSource 直接上线接口失败。
            return StateSource.UNKNOWN;
        }
    }

    private static Instant requireSyncedAt(Instant syncedAt) {
        if (syncedAt == null) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 online 响应缺少 syncedAt");
        }
        return syncedAt;
    }

    private static OnlineRouting toRouting(RoutingResponse routing) {
        if (routing == null) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 online 响应缺少 routing");
        }
        // routing 用于说明当前请求是否落在 owner worker 上;remote 场景不在 armada 侧二次转发。
        return new OnlineRouting(
                requireText(routing.ownerWorkerId(), "routing.ownerWorkerId"),
                routing.ownerEndpoint(),
                requireText(routing.currentWorkerId(), "routing.currentWorkerId"),
                routing.local());
    }

    private static ProtocolAccountStatus toProtocolAccountStatus(StatusResponse response) {
        if (response == null) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 status 响应为空");
        }
        return new ProtocolAccountStatus(
                requireText(response.accountId(), "accountId"),
                response.state(),
                response.stateSource(),
                response.accountType(),
                response.lastStateSyncTime(),
                response.cooldownUntil(),
                response.reportedAt(),
                response.needReauth(),
                response.reauthReason(),
                response.workerId());
    }

    private static ProtocolProbeResult toProtocolProbeResult(ProbeResponse response) {
        if (response == null) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 probe 响应为空");
        }
        return new ProtocolProbeResult(
                response.ok(),
                response.probedAt(),
                response.latencyMs(),
                response.reasonCode());
    }

    private static BatchOnlineRequest toBatchRequest(BatchOnlineCommand command) {
        // 逐个 item 复用单账号转换规则,确保 batch 和 single 的凭据格式/代理字段完全一致。
        List<BatchOnlineRequestItem> items = command.items().stream()
                .map(HttpAccountLifecycleAdapter::toBatchRequestItem)
                .toList();
        return new BatchOnlineRequest(items, command.maxWaitMs());
    }

    private static BatchOnlineRequestItem toBatchRequestItem(BatchOnlineCommandItem item) {
        if (item == null) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层批量上线 item 为空");
        }
        String accountId = requireText(item.protocolAccountId(), "batch.items[].protocolAccountId");
        OnlineCommand command = requireCommand(item.command());

        // 注意这里传给协议层的 accountId 是 protocolAccountId,不是 armada 本地 account.id。
        return new BatchOnlineRequestItem(
                accountId,
                toWireFormat(command.format()),
                parseCredential(command.credentialJson(), command.format()),
                command.proxy());
    }

    private static BatchOnlineAccepted toBatchAccepted(BatchOnlineResponse response) {
        if (response == null) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 batch online 响应为空");
        }
        // summary 是聚合统计;results 是本 worker 实际处理项;remote 是归属其它 worker 的路由信息。
        return new BatchOnlineAccepted(
                requireRequestedAt(response.requestedAt()),
                response.elapsedMs(),
                toBatchSummary(response.summary()),
                toBatchResults(response.results()),
                toBatchRemoteRoutes(response.remote()));
    }

    private static Instant requireRequestedAt(Instant requestedAt) {
        if (requestedAt == null) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 batch online 响应缺少 requestedAt");
        }
        return requestedAt;
    }

    private static BatchOnlineSummary toBatchSummary(BatchOnlineSummaryResponse summary) {
        if (summary == null) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 batch online 响应缺少 summary");
        }
        // 保持字段一一映射,不要在 adapter 内重新计算统计,避免和协议层口径不一致。
        return new BatchOnlineSummary(
                summary.requested(),
                summary.local(),
                summary.remote(),
                summary.accepted(),
                summary.timeout(),
                summary.proxyRequired(),
                summary.error());
    }

    private static List<BatchOnlineItemResult> toBatchResults(List<BatchOnlineItemResponse> results) {
        if (results == null) {
            return List.of();
        }
        // results 只包含协议层本 worker 处理过的账号;remote 账号会在 remote 字段里返回。
        return results.stream()
                .map(item -> new BatchOnlineItemResult(
                        requireText(item.accountId(), "results[].accountId"),
                        toBatchStatus(item.result()),
                        item.retryAfterMs(),
                        item.error()))
                .toList();
    }

    private static List<BatchOnlineRemoteRoute> toBatchRemoteRoutes(List<BatchOnlineRemoteResponse> remote) {
        if (remote == null) {
            return List.of();
        }
        // 远端路由只做透传展示/诊断;当前切片不在 armada 侧自动重投到 ownerEndpoint。
        return remote.stream()
                .map(item -> new BatchOnlineRemoteRoute(
                        requireText(item.accountId(), "remote[].accountId"),
                        item.ownerWorkerId(),
                        item.ownerEndpoint(),
                        item.note()))
                .toList();
    }

    private static BatchOnlineResultStatus toBatchStatus(String result) {
        if (result == null || result.isBlank()) {
            return BatchOnlineResultStatus.ERROR;
        }
        // 协议层返回 snake_case 字符串;未知值按 ERROR 收口,避免新值导致反序列化失败。
        return switch (result) {
            case "accepted" -> BatchOnlineResultStatus.ACCEPTED;
            case "timeout" -> BatchOnlineResultStatus.TIMEOUT;
            case "proxy_required" -> BatchOnlineResultStatus.PROXY_REQUIRED;
            case "remote" -> BatchOnlineResultStatus.REMOTE;
            case "error" -> BatchOnlineResultStatus.ERROR;
            default -> BatchOnlineResultStatus.ERROR;
        };
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 online 参数缺失 " + fieldName);
        }
        return value;
    }

    /** 单账号 /online 请求体,字段名必须匹配协议层 HTTP 契约。 */
    private record OnlineRequest(String format, Map<String, Object> credential, ProxyDescriptor proxy) {
    }

    /** 批量 /online/batch 请求体,items 为账号命令列表,maxWaitMs 为本批投递等待时间。 */
    private record BatchOnlineRequest(List<BatchOnlineRequestItem> items, int maxWaitMs) {
    }

    /** 批量请求中的单账号项;accountId 这里指协议层账号 ID。 */
    private record BatchOnlineRequestItem(
            String accountId,
            String format,
            Map<String, Object> credential,
            ProxyDescriptor proxy) {
    }

    /** 单账号 /online 响应体。 */
    private record OnlineResponse(
            String accountId,
            boolean accepted,
            String stateSource,
            Instant syncedAt,
            RoutingResponse routing) {
    }

    /** /status 响应体。 */
    private record StatusResponse(
            String accountId,
            String state,
            String stateSource,
            String accountType,
            Instant lastStateSyncTime,
            Instant cooldownUntil,
            Instant reportedAt,
            boolean needReauth,
            String reauthReason,
            String workerId) {
    }

    /** /probe 响应体。 */
    private record ProbeResponse(
            boolean ok,
            Instant probedAt,
            Long latencyMs,
            String reasonCode) {
    }

    /** 协议层 worker 路由信息,用于判断当前 worker 是否为账号 owner。 */
    private record RoutingResponse(
            String ownerWorkerId,
            String ownerEndpoint,
            String currentWorkerId,
            boolean local) {
    }

    /** 批量 /online/batch 响应体。 */
    private record BatchOnlineResponse(
            Instant requestedAt,
            long elapsedMs,
            BatchOnlineSummaryResponse summary,
            List<BatchOnlineItemResponse> results,
            List<BatchOnlineRemoteResponse> remote) {
    }

    /** 批量响应的统计区,按协议层返回口径直接映射。 */
    private record BatchOnlineSummaryResponse(
            int requested,
            int local,
            int remote,
            int accepted,
            int timeout,
            int proxyRequired,
            int error) {
    }

    /** 本 worker 对单个账号的批量投递结果。 */
    private record BatchOnlineItemResponse(
            String accountId,
            String result,
            Integer retryAfterMs,
            String error) {
    }

    /** 账号归属其它协议 worker 时返回的路由提示。 */
    private record BatchOnlineRemoteResponse(
            String accountId,
            String ownerWorkerId,
            String ownerEndpoint,
            String note) {
    }
}
