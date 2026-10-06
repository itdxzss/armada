package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.model.command.CreatorDeletionCommand;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 任务级短期授权，仅持有后端配置密钥，绑定 HTTP 操作及不可变业务身份。 */
@Component
public final class CreatorDeletionTaskSigner {
    private static final long TTL_SECONDS = 120;
    private final byte[] secret;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 部署时从服务端密钥配置注入；未配置时所有注销请求关闭。 */
    public CreatorDeletionTaskSigner(
            @Value("${armada.protocol.account-deletion-task-secret:}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** 在持久化不可重发意图之前检查部署授权是否可用。 */
    public void requireConfigured() {
        if (secret.length < 32) {
            throw new ProtocolException(ProtocolErrorCode.BAD_REQUEST, "任务注销授权未配置");
        }
    }

    /** 签名从不回显到日志、前端或普通任务配置。 */
    public String sign(CreatorDeletionCommand command, String method, String path, long nowSeconds) {
        if (secret.length < 32 || command.tenantId() <= 0 || command.taskId() <= 0
                || command.executionId() <= 0 || command.creator().armadaAccountId() <= 0) {
            throw new ProtocolException(ProtocolErrorCode.BAD_REQUEST, "任务注销授权未配置或业务身份无效");
        }
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("tenantId", Long.toString(command.tenantId()));
        claims.put("taskId", Long.toString(command.taskId()));
        claims.put("executionId", Long.toString(command.executionId()));
        claims.put("accountId", command.creator().armadaAccountId().toString());
        claims.put("accountHash", command.identityHash());
        claims.put("createOperationId", command.createOperationId());
        claims.put("operationId", command.operationId());
        claims.put("method", method);
        claims.put("path", path);
        claims.put("iat", nowSeconds);
        claims.put("exp", nowSeconds + TTL_SECONDS);
        try {
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            String payload = encoder.encodeToString(mapper.writeValueAsBytes(claims));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return payload + "." + encoder.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception failure) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "无法生成任务注销授权");
        }
    }
}
