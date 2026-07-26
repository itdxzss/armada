package com.armada.promotion.channel.support;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** 生成渠道公开短码；唯一性最终由数据库唯一键兜底。 */
@Component
public class ChannelCodeGenerator {

    private static final char[] ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    private static final int LENGTH = 8;
    private final SecureRandom random = new SecureRandom();

    /**
     * 生成 8 位公开渠道码。
     *
     * <p>字符集排除了容易混淆的字符，SecureRandom 降低可预测性，数据库唯一键负责最终去重。</p>
     *
     * @return 小写字母和数字组成的渠道码
     */
    public String generate() {
        char[] value = new char[LENGTH];
        for (int i = 0; i < value.length; i++) {
            value[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(value);
    }
}
