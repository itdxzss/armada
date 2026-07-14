package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 Android Zhuan 的多形态响应包解码为稳定的内部模型。
 */
public final class AndroidResponseDecoder {

    private static final int VALIDATION_ERROR_CODE = 1002;
    private static final Pattern RAW_CODE_PATTERN =
            Pattern.compile("(?i)\\bCode:\\s*([^,\\s]+)");

    /**
     * 解码 Android 原生响应，并拒绝既没有 Code 也没有 Gin 错误的未知结构。
     *
     * @param envelope Android 原生响应包
     * @return 解码后的稳定响应模型
     * @throws ProtocolException 响应为空或缺少可识别状态时抛出
     */
    public AndroidDecodedResponse decode(AndroidResponseEnvelope envelope) {
        if (envelope == null) {
            throw unrecognized("Android 响应为空");
        }
        String validationError = text(envelope.validationError());
        if (envelope.code() == null && validationError == null) {
            throw unrecognized("Android 响应缺少 Code");
        }
        String message = firstText(envelope.message(), envelope.data());
        return new AndroidDecodedResponse(
                envelope.code() == null ? VALIDATION_ERROR_CODE : envelope.code(),
                envelope.data(),
                message,
                validationError,
                rawCode(message));
    }

    private static String firstText(JsonNode primary, JsonNode fallback) {
        String value = nodeText(primary);
        return value != null ? value : nodeText(fallback);
    }

    private static String nodeText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return text(node.isTextual() ? node.asText() : node.toString());
    }

    private static String rawCode(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = RAW_CODE_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ProtocolException unrecognized(String message) {
        return new ProtocolException(
                ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED,
                message);
    }
}
