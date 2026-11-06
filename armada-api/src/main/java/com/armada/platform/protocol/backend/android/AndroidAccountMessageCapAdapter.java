package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolMessageCap;
import com.armada.platform.protocol.routing.AccountMessageCapBackend;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Android Zhuan 新聊天额度查询 backend。
 *
 * <p>Code=0 解析为额度结果；Zhuan 在平台拒绝或响应无效时返回带 {@code stage} 的结构化诊断，
 * 这类“明确拒绝”映射为 REJECTED 结果而不是异常，便于运营和实验查看原因；账号离线、网络、
 * 结构异常等仍以协议异常向上抛出。任何缺失字段都保持为空，不推断额度。</p>
 */
public final class AndroidAccountMessageCapAdapter implements AccountMessageCapBackend {

    private static final String MESSAGE_CAP_OPERATION = "account.messageCap";
    private static final String ACCOUNT_OPERATION_PREFIX = "account:";
    /** Zhuan vo.AccountOfflineErrorCode：账号没有现成在线会话。 */
    private static final int ACCOUNT_OFFLINE_CODE = 1010;
    private static final String STAGE_FIELD = "stage";

    private final AndroidNativeClient client;
    private final AndroidResponseDecoder decoder;
    private final AndroidGroupJoinErrorMapper errorMapper;

    /**
     * 创建 Android 额度查询 adapter。
     *
     * @param client Android 原生 HTTP client
     * @param decoder Android 原生响应 decoder
     * @param errorMapper Android 原生业务错误 mapper
     */
    public AndroidAccountMessageCapAdapter(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupJoinErrorMapper errorMapper) {
        this.client = client;
        this.decoder = decoder;
        this.errorMapper = errorMapper;
    }

    /**
     * 返回当前实现支持的 Android 协议后端。
     *
     * @return Android 协议后端
     */
    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.ANDROID;
    }

    /**
     * 查询 Android 协议账号的新聊天额度并附加统一诊断上下文。
     *
     * @param account 统一协议账号引用
     * @param debug 是否附带原始响应与完整解码内容
     * @return 额度结果；平台明确拒绝时 resultStatus 为 REJECTED
     * @throws ProtocolException 账号离线、响应结构、网络或未知应用层失败时抛出
     */
    @Override
    public ProtocolMessageCap query(ProtocolAccountRef account, boolean debug) {
        String operationId = ACCOUNT_OPERATION_PREFIX + account.armadaAccountId();
        try {
            AndroidDecodedResponse response = decoder.decode(client.messageCap(account.wsPhone(), debug));
            if (response.success()) {
                return snapshot(response.data());
            }
            if (response.code() == ACCOUNT_OFFLINE_CODE || errorMapper.isOffline(response)) {
                throw new ProtocolException(ProtocolErrorCode.ACCOUNT_NOT_ONLINE, "账号没有可用的在线会话");
            }
            JsonNode data = response.data();
            if (data != null && data.isObject() && data.hasNonNull(STAGE_FIELD)) {
                return rejected(data);
            }
            throw errorMapper.toException(response, account, MESSAGE_CAP_OPERATION, operationId);
        } catch (ProtocolException ex) {
            if (ex.backend().isPresent()) {
                throw ex;
            }
            throw ex.withContext(ProtocolBackend.ANDROID, MESSAGE_CAP_OPERATION, operationId);
        }
    }

    private static ProtocolMessageCap snapshot(JsonNode data) {
        if (data == null || !data.isObject() || text(data, "resultStatus") == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED, "额度响应缺少 resultStatus");
        }
        JsonNode subscription = data.path("subscriptionStatus");
        return new ProtocolMessageCap(
                text(data, "resultStatus"), text(data, "totalQuota"), text(data, "usedQuota"),
                text(data, "remaining"), text(data, "cappingStatus"), text(data, "cycleStart"),
                text(data, "cycleEnd"), text(data, "serverSentTimestamp"), text(data, "mvStatus"),
                text(data, "oteStatus"), text(subscription, "status"), text(subscription, "name"),
                nodeOrNull(data, "extensions"), text(data, "fetchedAt"), null, nodeOrNull(data, "debug"));
    }

    private static ProtocolMessageCap rejected(JsonNode failure) {
        return new ProtocolMessageCap(
                ProtocolMessageCap.REJECTED, null, null, null, null, null, null, null, null, null,
                null, null, null, null, failure, nodeOrNull(failure, "debug"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isValueNode() && !value.isNull() ? value.asText() : null;
    }

    private static JsonNode nodeOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value;
    }
}
