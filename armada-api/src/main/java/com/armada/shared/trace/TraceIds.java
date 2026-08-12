package com.armada.shared.trace;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 全链路追踪标识的格式校验、生成和兼容解析工具。
 */
public final class TraceIds {

    /** HTTP 请求和响应使用的追踪头。 */
    public static final String HTTP_HEADER = "X-Trace-Id";

    /** Kafka 消息使用的追踪头。 */
    public static final String KAFKA_HEADER = "traceId";

    private static final Pattern CANONICAL = Pattern.compile("[0-9a-f]{32}");
    private static final String ZERO_TRACE_ID = "00000000000000000000000000000000";

    private TraceIds() {
    }

    /**
     * 接受规范的追踪标识，拒绝大小写错误、全零或包含额外字符的值。
     *
     * @param candidate 待校验值
     * @return 合法的原值，非法时为空
     */
    public static Optional<String> normalize(String candidate) {
        if (candidate == null
                || !CANONICAL.matcher(candidate).matches()
                || ZERO_TRACE_ID.equals(candidate)) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    /**
     * 判断追踪标识是否符合统一格式。
     *
     * @param candidate 待校验值
     * @return 合法时为 {@code true}
     */
    public static boolean isValid(String candidate) {
        return normalize(candidate).isPresent();
    }

    /**
     * 生成新的规范追踪标识。
     *
     * @return 32 位小写十六进制追踪标识
     */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 从稳定种子派生追踪标识；种子为空时生成随机值。
     *
     * @param seed 稳定业务种子
     * @return 规范追踪标识
     */
    public static String stableFrom(String seed) {
        if (seed == null || seed.isBlank()) {
            return newTraceId();
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));
            String traceId = HexFormat.of().formatHex(digest, 0, 16);
            return ZERO_TRACE_ID.equals(traceId)
                    ? "00000000000000000000000000000001"
                    : traceId;
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /**
     * 按 Envelope、Kafka Header、稳定种子、随机值的优先级解析追踪标识。
     *
     * @param envelopeTraceId Envelope 中的追踪标识
     * @param headerTraceId Kafka Header 中的追踪标识
     * @param stableSeed 旧消息兼容使用的稳定种子
     * @return 解析后的追踪标识、来源和冲突状态
     */
    public static Resolution resolveCandidates(
            String envelopeTraceId,
            String headerTraceId,
            String stableSeed) {
        Optional<String> envelope = normalize(envelopeTraceId);
        Optional<String> header = normalize(headerTraceId);
        boolean mismatch = envelope.isPresent()
                && header.isPresent()
                && !envelope.get().equals(header.get());
        if (envelope.isPresent()) {
            return new Resolution(envelope.get(), Source.ENVELOPE, mismatch);
        }
        if (header.isPresent()) {
            return new Resolution(header.get(), Source.HEADER, false);
        }
        if (stableSeed != null && !stableSeed.isBlank()) {
            return new Resolution(stableFrom(stableSeed), Source.STABLE, false);
        }
        return new Resolution(newTraceId(), Source.RANDOM, false);
    }

    /** 追踪标识的解析来源。 */
    public enum Source {
        /** 消息 Envelope。 */
        ENVELOPE,
        /** Kafka Header。 */
        HEADER,
        /** 稳定种子派生值。 */
        STABLE,
        /** 新生成的随机值。 */
        RANDOM
    }

    /**
     * 追踪标识候选解析结果。
     *
     * @param traceId 最终追踪标识
     * @param source 最终值来源
     * @param mismatch Envelope 与 Header 是否存在合法但不一致的值
     */
    public record Resolution(String traceId, Source source, boolean mismatch) {
    }
}
