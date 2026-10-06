package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.http.ProtocolHttpExecutorRegistry;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.command.CreatorDeletionCommand;
import com.armada.platform.protocol.model.command.CreatorDeletionObservationRequest;
import com.armada.platform.protocol.model.result.CreatorDeletionResult;
import com.armada.platform.protocol.model.result.CreatorDeletionObservation;
import com.armada.platform.protocol.port.CreatorAccountDeletionPort;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 复用 Android 删除 IQ 和持久记录；不根据 HTTP cleanupComplete 推断服务端清理。 */
@Component
public final class AndroidCreatorAccountDeletionAdapter implements CreatorAccountDeletionPort {
    private static final String DELETE_PATH = "/ws/v1/account/delete/";
    private static final String QUERY_PATH = "/ws/v1/account/deletion/";
    private static final String PROOF_PATH = "/ws/v1/account/deletion-proof/";
    private final ProtocolHttpExecutorRegistry clients;
    private final CreatorDeletionTaskSigner signer;

    /** 通过现有 Android coordinator 路由，保留普通 API 鉴权。 */
    public AndroidCreatorAccountDeletionAdapter(ProtocolHttpExecutorRegistry clients,
            CreatorDeletionTaskSigner signer) {
        this.clients = clients;
        this.signer = signer;
    }

    @Override
    public CreatorDeletionResult delete(CreatorDeletionCommand command) {
        return request(command, "POST", DELETE_PATH, Map.of(
                "operationId", command.operationId(), "confirmAccountHash", command.identityHash(),
                "confirmPermanentDeletion", true));
    }

    @Override
    public CreatorDeletionResult query(CreatorDeletionCommand command) {
        return request(command, "GET", QUERY_PATH, null);
    }

    private CreatorDeletionResult request(CreatorDeletionCommand command,
            String method, String prefix, Object body) {
        requireAndroid(command.creator().backend());
        String phone = phone(command.creator().wsPhone());
        if (!hash(phone).equals(command.identityHash())) {
            throw invalid("冻结的建群账号身份不匹配");
        }
        String path = prefix + phone;
        JsonNode response = clients.required(ProtocolBackend.ANDROID).accountDeletion(method,
                path, body, signer.sign(command, method, path, System.currentTimeMillis() / 1000));
        JsonNode operation = response.path("Data").path("operation");
        String operationId = text(operation, "operationId");
        String accountHash = text(operation, "accountHash");
        if (!command.operationId().equals(operationId) || !command.identityHash().equals(accountHash)) {
            throw invalid("注销结果与冻结账号或 operationId 不匹配");
        }
        CreatorDeletionResult result = new CreatorDeletionResult(operationId, accountHash, text(operation, "state"),
                optionalText(operation, "iqId"), optionalText(operation, "responseType"),
                optionalText(operation, "reason"));
        if (result.accepted() && (!response.path("Code").isIntegralNumber() || response.path("Code").intValue() != 0)) {
            throw invalid("注销接受结果与协议响应状态冲突");
        }
        return result;
    }

    @Override
    public CreatorDeletionObservation observe(CreatorDeletionObservationRequest request) {
        signer.requireConfigured();
        if (request.manager().armadaAccountId().equals(request.creator().armadaAccountId())
                || phone(request.manager().wsPhone()).equals(phone(request.creator().wsPhone()))) {
            throw invalid("接管管理号不能是建群账号");
        }
        if (request.groupJid() == null || !request.groupJid().endsWith("@g.us")) {
            throw invalid("群身份无效");
        }
        long startedAt = System.currentTimeMillis();
        Map<String, String> body = Map.of("groupJid", request.groupJid(),
                "creatorPhone", phone(request.creator().wsPhone()));
        JsonNode proof;
        if (request.manager().backend() == ProtocolBackend.WEB) {
            proof = clients.required(ProtocolBackend.WEB).postTyped(
                    "/v1/accounts/{accountId}/deletion-proof", body, JsonNode.class,
                    request.manager().protocolAccountId());
        } else {
            AndroidResponseEnvelope response = clients.required(ProtocolBackend.ANDROID).postTyped(
                    PROOF_PATH + phone(request.manager().wsPhone()), body, AndroidResponseEnvelope.class);
            if (response == null || !Integer.valueOf(0).equals(response.code())) {
                throw invalid("管理号实时核验失败");
            }
            proof = response.data();
        }
        requireProof(request, proof, startedAt);
        return new CreatorDeletionObservation(text(proof, "groupJid"), creation(proof),
                text(proof, "creator"), text(proof, "creatorPN"), bool(proof, "managerPresent"),
                bool(proof, "managerAdmin"), bool(proof, "creatorPresent"),
                bool(proof, "registrationKnown"), bool(proof, "registered"),
                bool(proof, "structureComplete"), System.currentTimeMillis());
    }

    private static void requireProof(CreatorDeletionObservationRequest request, JsonNode proof, long startedAt) {
        if (proof == null || !proof.isObject()
                || !request.groupJid().equals(text(proof, "groupJid"))
                || !phone(request.manager().wsPhone()).equals(text(proof, "managerPhone"))
                || !"LIVE_IQ".equals(text(proof, "source"))
                || !bool(proof, "structureComplete") || !bool(proof, "registrationKnown")
                || creation(proof) <= 0
                || !proof.path("queriedAt").isIntegralNumber()
                || proof.path("queriedAt").longValue() < startedAt - 30_000
                || proof.path("queriedAt").longValue() > System.currentTimeMillis() + 30_000) {
            throw invalid("协议实时证明缺失、过期或结构异常");
        }
    }

    private static long creation(JsonNode proof) {
        JsonNode value = proof.path("creation");
        if ((!value.isTextual() && !value.isIntegralNumber())
                || !value.asText().matches("[1-9][0-9]{0,18}")) {
            throw invalid("协议证明缺少有效创建时间");
        }
        try {
            return Long.parseLong(value.asText());
        } catch (NumberFormatException exception) {
            throw invalid("协议证明创建时间越界");
        }
    }

    private static boolean bool(JsonNode node, String key) {
        if (!node.path(key).isBoolean()) {
            throw invalid("协议证明缺少布尔字段 " + key);
        }
        return node.get(key).booleanValue();
    }

    private static String text(JsonNode node, String key) {
        if (node == null || !node.path(key).isTextual()) {
            throw invalid("协议证明缺少字符串字段 " + key);
        }
        return node.get(key).textValue();
    }

    private static String optionalText(JsonNode node, String key) {
        return node.path(key).isTextual() ? node.get(key).textValue() : "";
    }

    private static void requireAndroid(ProtocolBackend backend) {
        if (backend != ProtocolBackend.ANDROID) {
            throw new ProtocolException(ProtocolErrorCode.UNSUPPORTED_BACKEND,
                    "当前协议不能提供安全注销所需的独立实时证明");
        }
    }

    private static String phone(String value) {
        String normalized = value == null ? "" : value.trim().replace("+", "").replace(" ", "");
        if (!normalized.matches("[1-9][0-9]{5,19}")) {
            throw invalid("冻结账号号码格式无效");
        }
        return normalized;
    }

    private static String hash(String phone) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(phone.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static ProtocolException invalid(String reason) {
        return new ProtocolException(ProtocolErrorCode.UNKNOWN, reason);
    }
}
