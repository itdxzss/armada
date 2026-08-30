package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.model.vo.HyperlinkTaskQuoteVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 自包含 HMAC 报价票据；数据库仅保存 quoteId，不保存 token 明文。 */
@Service
public class HyperlinkQuoteTokenService {
    private static final String ALGORITHM = "HmacSHA256";
    private final ObjectMapper objectMapper;
    private final byte[] signingKey;

    public HyperlinkQuoteTokenService(ObjectMapper objectMapper,
            @Value("${armada.hyperlink.quote-signing-key:}") String signingKey) {
        this.objectMapper = objectMapper;
        this.signingKey = signingKey.getBytes(StandardCharsets.UTF_8);
    }

    /** 为已完成后端报价签发短期不可篡改票据。 */
    public String sign(QuoteClaims claims) {
        ensureConfigured();
        try {
            String payload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(claims));
            return payload + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac(payload.getBytes(StandardCharsets.US_ASCII)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化报价票据", exception);
        }
    }

    /** 验证签名、归属、用途、任务版本和失效时间。 */
    public QuoteClaims verify(String token, long tenantId, long userId, String purpose,
            Long taskId, Integer taskVersion, long now) {
        ensureConfigured();
        if (token == null || token.isBlank() || token.indexOf('.') <= 0) {
            throw stale("quoteToken 缺失或格式错误");
        }
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || !java.security.MessageDigest.isEqual(
                    mac(parts[0].getBytes(StandardCharsets.US_ASCII)),
                    Base64.getUrlDecoder().decode(parts[1]))) {
                throw stale("quoteToken 签名无效");
            }
            QuoteClaims claims = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(parts[0]), QuoteClaims.class);
            if (claims.tenantId() != tenantId || claims.userId() != userId
                    || !purpose.equals(claims.purpose())
                    || !java.util.Objects.equals(taskId, claims.taskId())
                    || !java.util.Objects.equals(taskVersion, claims.taskVersion())
                    || claims.expiresAt() < now) {
                throw stale("报价已过期或不属于当前操作");
            }
            return claims;
        } catch (IllegalArgumentException | IOException exception) {
            throw stale("quoteToken 无法解析");
        }
    }

    private byte[] mac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, ALGORITHM));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("报价签名算法不可用", exception);
        }
    }

    private void ensureConfigured() {
        if (signingKey.length < 32) {
            throw new BusinessException(ErrorCode.HYPERLINK_BILLING_UNAVAILABLE,
                    "报价签名密钥未配置，任务启用门禁保持关闭");
        }
    }

    private BusinessException stale(String message) {
        return new BusinessException(ErrorCode.HYPERLINK_QUOTE_STALE, message);
    }

    /** token 内冻结的全部账务及数据包事实。 */
    public record QuoteClaims(
            String quoteId,
            long tenantId,
            long userId,
            String purpose,
            Long taskId,
            Integer taskVersion,
            long dataPackageId,
            int dataPackageGeneration,
            long claimUpperPhoneId,
            String taskMode,
            int maxExecutingAccounts,
            int maxUseAccounts,
            String billingProvider,
            long expiresAt,
            HyperlinkTaskQuoteVO quote) { }
}
