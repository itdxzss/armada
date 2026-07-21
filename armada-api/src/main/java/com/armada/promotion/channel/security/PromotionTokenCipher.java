package com.armada.promotion.channel.security;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 使用 AES-256-GCM 加密广告平台 Access Token；不会记录或返回明文。 */
@Component
public class PromotionTokenCipher {

    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final String encodedKey;
    private final String keyId;
    private final SecureRandom secureRandom;

    /**
     * 由环境配置构造 Token 加密器。
     *
     * @param encodedKey Base64 编码的 32 字节 AES 密钥
     * @param keyId 密钥版本，用于后续轮换和解密定位
     */
    @Autowired
    public PromotionTokenCipher(
            @Value("${armada.promotion.tracking.encryption-key:}") String encodedKey,
            @Value("${armada.promotion.tracking.encryption-key-id:}") String keyId) {
        this(encodedKey, keyId, new SecureRandom());
    }

    PromotionTokenCipher(String encodedKey, String keyId, SecureRandom secureRandom) {
        this.encodedKey = encodedKey;
        this.keyId = keyId;
        this.secureRandom = secureRandom;
    }

    /**
     * 加密 Access Token 并计算不可逆指纹。
     *
     * <p>密文格式为 {@code 12字节随机nonce + GCM密文及认证标签}。指纹只用于判断 Token 是否变化，不可用于还原明文。</p>
     *
     * @param plaintext Access Token 明文
     * @return 密文、密钥版本和 SHA-256 指纹
     */
    public EncryptedToken encrypt(String plaintext) {
        if (!StringUtils.hasText(plaintext)) {
            throw new BusinessException(ErrorCode.VALIDATION, "Access Token 不能为空");
        }
        byte[] key = decodeKey();
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            // 每次加密使用独立随机 nonce；GCM 同时提供机密性和完整性校验。
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce)
                    .put(encrypted)
                    .array();

            // 指纹与密文分离保存，后续更新可在不解密的情况下判断 Token 是否发生变化。
            byte[] fingerprint = MessageDigest.getInstance("SHA-256")
                    .digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedToken(payload, keyId, fingerprint);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Access Token 加密失败", ex);
        }
    }

    /** 解码并校验环境密钥；不允许缺少密钥时退化为明文保存。 */
    private byte[] decodeKey() {
        if (!StringUtils.hasText(encodedKey)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION,
                    "系统未配置推广平台 Token 加密密钥，暂不能保存 Access Token");
        }
        final byte[] key;
        try {
            key = Base64.getDecoder().decode(encodedKey.trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "推广平台 Token 加密密钥格式错误");
        }
        if (key.length != 32) {
            throw new BusinessException(ErrorCode.VALIDATION, "推广平台 Token 加密密钥必须是 Base64 编码的 32 字节密钥");
        }
        if (!StringUtils.hasText(keyId)) {
            throw new BusinessException(ErrorCode.VALIDATION, "推广平台 Token 加密密钥版本不能为空");
        }
        return key;
    }

    /** Token 安全落库所需的三个值，不包含明文。 */
    public record EncryptedToken(byte[] ciphertext, String keyId, byte[] fingerprint) {
    }
}
