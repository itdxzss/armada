package com.armada.platform.auth.service.impl;

import com.armada.platform.auth.config.AuthProperties;
import com.armada.platform.auth.exception.AuthInfrastructureException;
import com.armada.platform.auth.model.AuthSession;
import com.armada.platform.auth.model.CreatedSession;
import com.armada.platform.auth.service.SessionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** Redis Lua 保证双键一致性的单用户单会话实现。 */
@Service
public class RedisSessionService implements SessionService {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionService.class);
    private static final String SESSION_PREFIX = "auth:session:";
    private static final String USER_PREFIX = "auth:user-session:";
    private static final int TOKEN_BYTES = 32;
    private static final DefaultRedisScript<Long> CREATE_SCRIPT = new DefaultRedisScript<>("""
            local old = redis.call('GET', KEYS[2])
            if old then redis.call('DEL', ARGV[1] .. old) end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[4])
            redis.call('SET', KEYS[2], ARGV[3], 'PX', ARGV[4])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[2]) ~= ARGV[1] then
                redis.call('DEL', KEYS[1])
                return 0
            end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            redis.call('PEXPIRE', KEYS[2], ARGV[3])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> LOGOUT_SCRIPT = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1])
            if redis.call('GET', KEYS[2]) == ARGV[1] then return redis.call('DEL', KEYS[2]) end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AuthProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RedisSessionService(
            @Qualifier("authRedisTemplate") StringRedisTemplate redis,
            ObjectMapper objectMapper,
            AuthProperties properties) {
        this(redis, objectMapper, properties, Clock.systemUTC());
    }

    RedisSessionService(StringRedisTemplate redis, ObjectMapper objectMapper, AuthProperties properties, Clock clock) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public CreatedSession create(long userId, long tenantId) {
        long now = clock.millis();
        long absoluteExpiresAt = now + properties.getSessionMaxLifetime().toMillis();
        String token = newToken();
        String tokenHash = hash(token);
        AuthSession session = new AuthSession(userId, tenantId, now, now, absoluteExpiresAt);
        try {
            Long created = redis.execute(CREATE_SCRIPT,
                    List.of(sessionKey(tokenHash), userKey(userId)),
                    SESSION_PREFIX, json(session), tokenHash,
                    String.valueOf(ttl(now, absoluteExpiresAt).toMillis()));
            if (!Long.valueOf(1L).equals(created)) {
                throw new AuthInfrastructureException("登录会话创建失败", null);
            }
            log.info("auth.session.create.ok userId={} tenantId={} idleTimeoutSeconds={} absoluteExpiresAt={}",
                    userId, tenantId, properties.getSessionIdleTimeout().toSeconds(), absoluteExpiresAt);
            return new CreatedSession(
                    token, properties.getSessionIdleTimeout().toSeconds(), absoluteExpiresAt);
        } catch (RuntimeException ex) {
            throw unavailable(ex);
        }
    }

    @Override
    public Optional<AuthSession> resolve(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = hash(rawToken);
        try {
            String value = redis.opsForValue().get(sessionKey(tokenHash));
            if (value == null) {
                log.debug("auth.session.resolve.reject reason=missing_or_expired");
                return Optional.empty();
            }
            AuthSession stored = objectMapper.readValue(value, AuthSession.class);
            long now = clock.millis();
            if (now >= stored.absoluteExpiresAt()) {
                log.debug("auth.session.resolve.reject reason=absolute_expired userId={} tenantId={}",
                        stored.userId(), stored.tenantId());
                logout(rawToken);
                return Optional.empty();
            }
            AuthSession renewed = new AuthSession(stored.userId(), stored.tenantId(), stored.issuedAt(),
                    now, stored.absoluteExpiresAt());
            Long result = redis.execute(RENEW_SCRIPT,
                    List.of(sessionKey(tokenHash), userKey(stored.userId())), tokenHash,
                    json(renewed), String.valueOf(ttl(now, stored.absoluteExpiresAt()).toMillis()));
            if (!Long.valueOf(1L).equals(result)) {
                log.debug("auth.session.resolve.reject reason=session_replaced userId={} tenantId={}",
                        stored.userId(), stored.tenantId());
                return Optional.empty();
            }
            return Optional.of(renewed);
        } catch (JsonProcessingException | RuntimeException ex) {
            throw unavailable(ex);
        }
    }

    @Override
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        String tokenHash = hash(rawToken);
        try {
            String value = redis.opsForValue().get(sessionKey(tokenHash));
            if (value == null) {
                return;
            }
            AuthSession session = objectMapper.readValue(value, AuthSession.class);
            redis.execute(LOGOUT_SCRIPT,
                    List.of(sessionKey(tokenHash), userKey(session.userId())), tokenHash);
            log.info("auth.session.logout.ok userId={} tenantId={}", session.userId(), session.tenantId());
        } catch (JsonProcessingException | RuntimeException ex) {
            throw unavailable(ex);
        }
    }

    @Override
    public void invalidateUser(long userId) {
        try {
            String tokenHash = redis.opsForValue().getAndDelete(userKey(userId));
            if (tokenHash != null) {
                redis.delete(sessionKey(tokenHash));
            }
            log.info("auth.session.invalidate userId={} hadSession={}", userId, tokenHash != null);
        } catch (RuntimeException ex) {
            throw unavailable(ex);
        }
    }

    private Duration ttl(long now, long absoluteExpiresAt) {
        return Duration.ofMillis(Math.min(
                properties.getSessionIdleTimeout().toMillis(), absoluteExpiresAt - now));
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 缺少 SHA-256", ex);
        }
    }

    private String json(AuthSession session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (JsonProcessingException ex) {
            throw new AuthInfrastructureException("登录会话序列化失败", ex);
        }
    }

    private static String sessionKey(String tokenHash) { return SESSION_PREFIX + tokenHash; }
    private static String userKey(long userId) { return USER_PREFIX + userId; }

    private static AuthInfrastructureException unavailable(Exception ex) {
        return ex instanceof AuthInfrastructureException authException
                ? authException : new AuthInfrastructureException("登录会话服务不可用", ex);
    }
}
