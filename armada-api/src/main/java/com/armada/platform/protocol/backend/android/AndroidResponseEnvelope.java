package com.armada.platform.protocol.backend.android;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Android Zhuan 原生接口的通用响应包。
 *
 * @param code Android 应用层状态码；Gin 参数校验失败时可能缺失
 * @param data Android 原生响应数据，可能是文本、对象或 null
 * @param message Android 原生消息，可能是文本、对象或 null
 * @param validationError Gin 参数绑定错误
 */
public record AndroidResponseEnvelope(
        @JsonProperty("Code") Integer code,
        @JsonProperty("Data") JsonNode data,
        @JsonProperty("Msg") JsonNode message,
        @JsonProperty("error") String validationError) {
}
