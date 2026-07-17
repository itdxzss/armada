package com.armada.platform.protocol.backend.web;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolAccountRuntimeStatus;
import com.armada.platform.protocol.routing.AccountRuntimeStatusBackend;

/**
 * Web/Baileys 原生账号运行态 backend。
 *
 * <p>保持现有 {@code GET /v1/accounts/{protocolAccountId}/status} 契约，只把 Web 状态字段
 * 转换为 Armada 统一运行态。</p>
 */
public final class WebAccountRuntimeStatusAdapter implements AccountRuntimeStatusBackend {

    private static final String ACCOUNT_URI_PREFIX = "/v1/accounts/";
    private static final String STATUS_URI_SUFFIX = "/status";
    private static final String STATUS_OPERATION = "account.status";
    private static final String ACCOUNT_OPERATION_PREFIX = "account:";

    private final ProtocolHttpExecutor httpExecutor;

    /**
     * 创建 Web 账号运行态 adapter。
     *
     * @param httpExecutor Web 协议后端 HTTP 执行器
     */
    public WebAccountRuntimeStatusAdapter(ProtocolHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    /**
     * 返回当前实现支持的 Web 协议后端。
     *
     * @return Web 协议后端
     */
    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.WEB;
    }

    /**
     * 查询 Web 协议账号当前运行态并附加统一诊断上下文。
     *
     * @param account 统一协议账号引用
     * @return 归一化后的账号运行态
     * @throws ProtocolException Web 协议调用失败时抛出
     */
    @Override
    public ProtocolAccountRuntimeStatus status(ProtocolAccountRef account) {
        try {
            StatusResponse response = httpExecutor.getTyped(
                    ACCOUNT_URI_PREFIX + account.protocolAccountId() + STATUS_URI_SUFFIX,
                    StatusResponse.class);
            return new ProtocolAccountRuntimeStatus(response == null ? null : response.state());
        } catch (ProtocolException ex) {
            throw ex.withContext(
                    ProtocolBackend.WEB,
                    STATUS_OPERATION,
                    ACCOUNT_OPERATION_PREFIX + account.armadaAccountId());
        }
    }

    private record StatusResponse(String accountId, String state) {
    }
}
