package com.armada.hyperlink.task.service;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** 生成不可顺序枚举、URL 安全且大小写敏感的 recipient 短码。 */
@Component
public class HyperlinkShortCodeGenerator {
    private static final int RANDOM_BYTE_COUNT = 12;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 生成 16 字符的 CSPRNG 短码。
     *
     * @return 满足 H6 12～24 字符合同的 Base64 URL 短码
     */
    public String next() {
        byte[] randomBytes = new byte[RANDOM_BYTE_COUNT];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
