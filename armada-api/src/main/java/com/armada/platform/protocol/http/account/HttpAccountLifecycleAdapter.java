package com.armada.platform.protocol.http.account;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.port.account.AccountLifecyclePort;
import com.armada.platform.protocol.port.account.command.CredentialFormat;
import com.armada.platform.protocol.port.account.command.OnlineCommand;
import com.armada.platform.protocol.port.account.command.ProxyDescriptor;
import com.armada.platform.protocol.port.account.result.OnlineAccepted;
import com.armada.platform.protocol.port.account.result.OnlineRouting;
import com.armada.platform.protocol.port.account.result.StateSource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

/**
 * {@link AccountLifecyclePort} 的 HTTP adapter。
 *
 * <p>本类是 account lifecycle port 到协议层 HTTP wire 契约的翻译边界:业务侧只看
 * {@link OnlineCommand} / {@link OnlineAccepted},协议层 URL、format 字符串、JSON body 形状都收口在这里。</p>
 */
public class HttpAccountLifecycleAdapter implements AccountLifecyclePort {

    private static final String ONLINE_URI_PREFIX = "/v1/accounts/";
    private static final String ONLINE_URI_SUFFIX = "/online";
    private static final String WIRE_FORMAT_SIX = "six";
    private static final String WIRE_FORMAT_BAILEYS_JSON = "baileys_json";
    private static final String WIRE_FORMAT_PARAMS = "params";
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
        String accountId = requireText(protocolAccountId, "protocolAccountId");
        OnlineCommand safeCommand = requireCommand(command);
        OnlineRequest request = new OnlineRequest(
                toWireFormat(safeCommand.format()),
                parseCredential(safeCommand.credentialJson(), safeCommand.format()),
                safeCommand.proxy());
        OnlineResponse response = httpExecutor.postTyped(onlineUri(accountId), request, OnlineResponse.class);
        return toAccepted(response);
    }

    private static String onlineUri(String protocolAccountId) {
        return ONLINE_URI_PREFIX + protocolAccountId + ONLINE_URI_SUFFIX;
    }

    private static OnlineCommand requireCommand(OnlineCommand command) {
        if (command == null) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层上线命令缺失");
        }
        return command;
    }

    private static String toWireFormat(CredentialFormat format) {
        if (format == null) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层上线凭据格式缺失");
        }
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
        return new OnlineRouting(
                requireText(routing.ownerWorkerId(), "routing.ownerWorkerId"),
                routing.ownerEndpoint(),
                requireText(routing.currentWorkerId(), "routing.currentWorkerId"),
                routing.local());
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 online 参数缺失 " + fieldName);
        }
        return value;
    }

    private record OnlineRequest(String format, Map<String, Object> credential, ProxyDescriptor proxy) {
    }

    private record OnlineResponse(
            String accountId,
            boolean accepted,
            String stateSource,
            Instant syncedAt,
            RoutingResponse routing) {
    }

    private record RoutingResponse(
            String ownerWorkerId,
            String ownerEndpoint,
            String currentWorkerId,
            boolean local) {
    }
}
