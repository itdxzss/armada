package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolAccountIntegrity;
import com.armada.platform.protocol.routing.AccountIntegrityBackend;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/**
 * Android Zhuan 只读档位查询 backend。
 *
 * <p>Zhuan 用在线会话发 MEX 查询，业务成败在响应体 Code；成功体的 {@code status} 与 Web 契约一致
 * （OK / EMPTY / NOT_ALLOWED / ERROR）。响应原文（含 debug 字段）不进入归一化结果，
 * 错误也只保留固定原因码，避免把协议返回内容泄漏到上层。</p>
 */
public final class AndroidAccountIntegrityAdapter implements AccountIntegrityBackend {

    private static final String OPERATION = "account.integrity";
    private static final String ACCOUNT_OPERATION_PREFIX = "account:";
    /** Zhuan vo.AccountOfflineErrorCode：账号没有现成在线会话。 */
    private static final int ACCOUNT_OFFLINE_CODE = 1010;
    private static final Set<String> REASONS = Set.of(
            "TIMEOUT", "IQ_ERROR", "GRAPHQL_ERROR", "INVALID_RESPONSE", "UNSUPPORTED_FORMAT", "INVALID_REQUEST");

    private final AndroidNativeClient client;
    private final AndroidResponseDecoder decoder;
    private final AndroidGroupJoinErrorMapper errorMapper;

    /**
     * 创建 Android 档位查询 adapter。
     *
     * @param client Android 原生 HTTP client
     * @param decoder Android 原生响应 decoder
     * @param errorMapper Android 原生业务错误 mapper
     */
    public AndroidAccountIntegrityAdapter(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupJoinErrorMapper errorMapper) {
        this.client = client;
        this.decoder = decoder;
        this.errorMapper = errorMapper;
    }

    /** 返回当前实现支持的 Android 协议后端。 */
    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.ANDROID;
    }

    /**
     * 用发起账号的在线会话查询目标号码档位。
     *
     * @param executor 发起查询的 Android 账号
     * @param targetPhone 被查询号码
     * @return 归一化档位结果；平台明确的空/拒绝/失败以结果状态表达
     * @throws ProtocolException 账号离线、响应结构异常或网络失败时抛出，消息不含响应原文
     */
    @Override
    public ProtocolAccountIntegrity query(ProtocolAccountRef executor, String targetPhone) {
        String operationId = ACCOUNT_OPERATION_PREFIX + executor.armadaAccountId();
        try {
            AndroidDecodedResponse response =
                    decoder.decode(client.accountIntegrity(executor.wsPhone(), targetPhone, false));
            if (response.success()) {
                return normalize(response.data());
            }
            if (response.code() == ACCOUNT_OFFLINE_CODE || errorMapper.isOffline(response)) {
                throw new ProtocolException(ProtocolErrorCode.ACCOUNT_NOT_ONLINE, "账号没有可用的在线会话");
            }
            throw errorMapper.toException(response, executor, OPERATION, operationId);
        } catch (ProtocolException ex) {
            // 不传播底层执行器或 mapper 可能携带的错误原文。
            throw new ProtocolException(ex.errorCode(), "档位协议查询失败")
                    .withContext(ProtocolBackend.ANDROID, OPERATION, operationId);
        }
    }

    private static ProtocolAccountIntegrity normalize(JsonNode data) {
        String status = text(data, "status");
        if (data == null || !data.isObject() || status == null) {
            return ProtocolAccountIntegrity.failure("INVALID_RESPONSE");
        }
        return switch (status) {
            case "EMPTY" -> new ProtocolAccountIntegrity(ProtocolAccountIntegrity.Status.EMPTY,
                    null, null, null, null, null, null, null, "EMPTY_RESPONSE");
            case "NOT_ALLOWED" -> new ProtocolAccountIntegrity(ProtocolAccountIntegrity.Status.NOT_ALLOWED,
                    null, null, null, null, null, null, null, "NOT_ALLOWED");
            case "ERROR" -> {
                String reason = text(data, "reasonCode");
                yield ProtocolAccountIntegrity.failure(reason != null && REASONS.contains(reason)
                        ? reason : "PROTOCOL_ERROR");
            }
            case "OK" -> success(data);
            default -> ProtocolAccountIntegrity.failure("INVALID_RESPONSE");
        };
    }

    private static ProtocolAccountIntegrity success(JsonNode data) {
        String tier = text(data, "trustTier");
        if (tier != null && !tier.matches("[A-Za-z0-9_]{1,32}")) {
            return ProtocolAccountIntegrity.failure("INVALID_RESPONSE");
        }
        Boolean suspicious = bool(data, "isSuspicious");
        Boolean banned = bool(data, "isBanned");
        Boolean mv = bool(data, "isMvSubscribed");
        Long joined = joinDate(data.path("joinDateMs"));
        String country = text(data, "phoneCountryCode");
        if (tier == null && suspicious == null && banned == null && mv == null
                && joined == null && country == null) {
            return ProtocolAccountIntegrity.failure("INVALID_RESPONSE");
        }
        String userType = text(data, "userType");
        if (!"BUSINESS".equals(userType) && !"PERSONAL".equals(userType)) {
            userType = null;
        }
        if (country != null && !country.matches("[A-Za-z0-9+_-]{1,32}")) {
            country = null;
        }
        return new ProtocolAccountIntegrity(ProtocolAccountIntegrity.Status.OK, userType, tier,
                suspicious, banned, mv, joined, country, null);
    }

    private static Long joinDate(JsonNode node) {
        if (!node.isNumber() || !node.canConvertToLong()) {
            return null;
        }
        long value = node.asLong();
        return value >= 0 ? value : null;
    }

    private static Boolean bool(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isBoolean() ? value.asBoolean() : null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
