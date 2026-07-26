package com.armada.promotion.pairing.service.impl;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 生成公开会话令牌并仅向数据库提供 SHA-256 摘要。 */
@Component
public class PromotionPairingTokenService {

    private static final int TOKEN_BYTES = 32;
    private static final int MAX_TOKEN_LENGTH = 128;
    private final SecureRandom secureRandom = new SecureRandom();

    /** 生成只返回给浏览器一次的高熵令牌及其摘要。 */
    public GeneratedToken generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new GeneratedToken(raw, hash(raw));
    }

    /** 对浏览器提交的令牌做相同摘要，避免明文令牌落库。 */
    public String hash(String rawToken) {
        if (!StringUtils.hasText(rawToken) || rawToken.length() > MAX_TOKEN_LENGTH) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "配对会话不存在");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 缺少 SHA-256", ex);
        }
    }

    /** 明文只存在请求内存，数据库仅保存 tokenHash。 */
    public record GeneratedToken(String rawToken, String tokenHash) {
    }
}
