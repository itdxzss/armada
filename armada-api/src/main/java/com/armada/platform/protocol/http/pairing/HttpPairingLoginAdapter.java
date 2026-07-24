package com.armada.platform.protocol.http.pairing;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.http.ProtocolHttpExecutorRegistry;
import com.armada.platform.protocol.model.command.PairingCodeCommand;
import com.armada.platform.protocol.model.command.ProxyDescriptor;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.PairingAccepted;
import com.armada.platform.protocol.model.result.PairingCredentialExport;
import com.armada.platform.protocol.port.PairingLoginPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** 复用现有 Web {@link ProtocolHttpExecutor} 的手机号配对 HTTP adapter。 */
@Component
public class HttpPairingLoginAdapter implements PairingLoginPort {

    private static final String PAIRING_URI = "/v1/auth/promotion-pairing-code";
    private static final String EXPORT_URI = "/v1/accounts/{accountId}/export/baileys-json";

    private final ProtocolHttpExecutor executor;
    private final ObjectMapper objectMapper;

    /**
     * 生产装配入口，明确只使用 Web/Baileys executor。
     *
     * @param registry 协议 HTTP executor 注册表
     * @param objectMapper 系统 JSON 编解码器
     */
    public HttpPairingLoginAdapter(ProtocolHttpExecutorRegistry registry, ObjectMapper objectMapper) {
        this(registry.required(ProtocolBackend.WEB), objectMapper);
    }

    /** 测试与显式装配入口。 */
    HttpPairingLoginAdapter(ProtocolHttpExecutor executor, ObjectMapper objectMapper) {
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    /** {@inheritDoc} */
    @Override
    public PairingAccepted requestCode(PairingCodeCommand command) {
        PairingRequest request = new PairingRequest(
                command.accountId(), command.phone(), command.proxy());
        PairingResponse response = executor.postTyped(PAIRING_URI, request, PairingResponse.class);
        return new PairingAccepted(response.accountId(), response.pairingId(), response.expiresAt());
    }

    /** {@inheritDoc} */
    @Override
    public PairingCredentialExport exportCredential(String protocolAccountId) {
        CredentialResponse response = executor.getSensitiveTyped(
                EXPORT_URI, CredentialResponse.class, protocolAccountId);
        if (response == null
                || !"baileys.auth_state.v1".equals(response.schema())
                || response.creds() == null || response.creds().isNull() || !response.creds().isObject()
                || response.keys() == null || response.keys().isNull() || !response.keys().isObject()) {
            throw new IllegalStateException("协议层未返回完整配对凭据");
        }
        try {
            // 保存完整 schema/creds/keys，后续普通上线可以直接按 BAILEYS_JSON 交给协议层。
            return new PairingCredentialExport(
                    protocolAccountId, objectMapper.writeValueAsString(response));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("协议账号凭据序列化失败", ex);
        }
    }

    /** 请求体故意没有 customPairingCode，随机码由协议层生成。 */
    private record PairingRequest(String accountId, String phone, ProxyDescriptor proxy) {
    }

    private record PairingResponse(String accountId, String pairingId, Instant expiresAt) {
    }

    private record CredentialResponse(String schema, JsonNode creds, JsonNode keys) {
    }
}
