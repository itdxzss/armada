package com.armada.platform.protocol.backend.android;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 解码后的 Android 原生响应。
 *
 * @param code Android 应用层状态码
 * @param data 原生业务数据
 * @param message 归一化后的安全解析源消息
 * @param validationError Gin 参数绑定错误
 * @param rawProtocolCode 原生消息中提取出的 WhatsApp IQ 错误码
 */
public record AndroidDecodedResponse(
        int code,
        JsonNode data,
        String message,
        String validationError,
        String rawProtocolCode) {

    private static final int SUCCESS_CODE = 0;

    /**
     * 判断 Android 应用层调用是否明确成功。
     *
     * @return Code 为 0 且不存在 Gin 参数错误时返回 true
     */
    public boolean success() {
        return code == SUCCESS_CODE
                && (validationError == null || validationError.isBlank());
    }
}
