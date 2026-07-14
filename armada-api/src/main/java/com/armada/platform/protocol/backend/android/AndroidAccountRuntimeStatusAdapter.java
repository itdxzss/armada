package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolAccountRuntimeStatus;
import com.armada.platform.protocol.routing.AccountRuntimeStatusBackend;

/**
 * Android Zhuan 原生账号运行态 backend。
 *
 * <p>只有 Code=0 明确映射 ONLINE，只有原生消息明确表达账号不存在或离线时映射 OFFLINE；
 * 网络、结构异常和未知应用失败继续以协议异常向上抛出。</p>
 */
public final class AndroidAccountRuntimeStatusAdapter implements AccountRuntimeStatusBackend {

    private static final String ONLINE_STATE = "ONLINE";
    private static final String OFFLINE_STATE = "OFFLINE";
    private static final String STATUS_OPERATION = "account.status";
    private static final String ACCOUNT_OPERATION_PREFIX = "account:";

    private final AndroidNativeClient client;
    private final AndroidResponseDecoder decoder;
    private final AndroidGroupJoinErrorMapper errorMapper;

    /**
     * 创建 Android 账号运行态 adapter。
     *
     * @param client Android 原生 HTTP client
     * @param decoder Android 原生响应 decoder
     * @param errorMapper Android 原生业务错误 mapper
     */
    public AndroidAccountRuntimeStatusAdapter(
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
     * 查询 Android 协议账号当前运行态并附加统一诊断上下文。
     *
     * @param account 统一协议账号引用
     * @return 明确的 ONLINE 或 OFFLINE 运行态
     * @throws ProtocolException 响应结构、网络或未知应用层失败时抛出
     */
    @Override
    public ProtocolAccountRuntimeStatus status(ProtocolAccountRef account) {
        String operationId = ACCOUNT_OPERATION_PREFIX + account.armadaAccountId();
        try {
            AndroidDecodedResponse response = decoder.decode(client.status(account.wsPhone()));
            if (response.success()) {
                return new ProtocolAccountRuntimeStatus(ONLINE_STATE);
            }
            if (errorMapper.isOffline(response)) {
                return new ProtocolAccountRuntimeStatus(OFFLINE_STATE);
            }
            throw errorMapper.toException(response, account, STATUS_OPERATION, operationId);
        } catch (ProtocolException ex) {
            if (ex.backend().isPresent()) {
                throw ex;
            }
            throw ex.withContext(
                    ProtocolBackend.ANDROID,
                    STATUS_OPERATION,
                    operationId);
        }
    }
}
