package com.armada.platform.auth.service.impl;

import com.armada.platform.auth.config.AuthProperties;
import com.armada.platform.auth.exception.AuthInfrastructureException;
import com.armada.platform.auth.model.CaptchaChallenge;
import com.armada.platform.auth.service.CaptchaService;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** 基于 JDK Java2D 和 Redis 的一次性图片验证码实现。 */
@Service
public class RedisCaptchaService implements CaptchaService {

    private static final Logger log = LoggerFactory.getLogger(RedisCaptchaService.class);
    private static final String KEY_PREFIX = "auth:captcha:";
    private static final String CHARACTERS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 4;
    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;

    private final StringRedisTemplate redis;
    private final AuthProperties properties;
    private final SecureRandom random = new SecureRandom();

    public RedisCaptchaService(
            @Qualifier("authRedisTemplate") StringRedisTemplate redis,
            AuthProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public CaptchaChallenge create() {
        String captchaId = UUID.randomUUID().toString();
        String answer = randomAnswer();
        try {
            redis.opsForValue().set(KEY_PREFIX + captchaId, answer, properties.getCaptchaTtl());
            log.debug("auth.captcha.create.ok ttlSeconds={}", properties.getCaptchaTtl().toSeconds());
            return new CaptchaChallenge(
                    captchaId,
                    "data:image/png;base64," + Base64.getEncoder().encodeToString(render(answer)),
                    properties.getCaptchaTtl().toSeconds());
        } catch (RuntimeException ex) {
            throw new AuthInfrastructureException("验证码服务不可用", ex);
        }
    }

    @Override
    public boolean consume(String captchaId, String answer) {
        if (captchaId == null || captchaId.isBlank() || answer == null || answer.isBlank()) {
            log.debug("auth.captcha.consume.reject reason=missing_input");
            return false;
        }
        try {
            String expected = redis.opsForValue().getAndDelete(KEY_PREFIX + captchaId.trim());
            if (expected == null) {
                log.debug("auth.captcha.consume.reject reason=missing_or_expired");
                return false;
            }
            boolean matches = expected.equals(answer.trim().toUpperCase(Locale.ROOT));
            if (!matches) {
                log.debug("auth.captcha.consume.reject reason=answer_mismatch");
            }
            return matches;
        } catch (RuntimeException ex) {
            throw new AuthInfrastructureException("验证码服务不可用", ex);
        }
    }

    private String randomAnswer() {
        StringBuilder answer = new StringBuilder(CODE_LENGTH);
        for (int index = 0; index < CODE_LENGTH; index++) {
            answer.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }
        return answer.toString();
    }

    private byte[] render(String answer) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
            for (int index = 0; index < CODE_LENGTH; index++) {
                graphics.setColor(new Color(random.nextInt(120), random.nextInt(120), random.nextInt(120)));
                graphics.drawString(String.valueOf(answer.charAt(index)), 13 + index * 25, 29);
            }
            graphics.setColor(new Color(150, 150, 150));
            for (int index = 0; index < 5; index++) {
                graphics.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT),
                        random.nextInt(WIDTH), random.nextInt(HEIGHT));
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("PNG 编码器不可用");
            }
            return output.toByteArray();
        } catch (Exception ex) {
            throw new AuthInfrastructureException("验证码图片生成失败", ex);
        } finally {
            graphics.dispose();
        }
    }
}
