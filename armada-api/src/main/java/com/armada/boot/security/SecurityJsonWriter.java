package com.armada.boot.security;

import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** 在 Spring MVC 之前输出统一认证错误 JSON。 */
@Component
public class SecurityJsonWriter {

    private final ObjectMapper objectMapper;

    public SecurityJsonWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 写入指定 HTTP 状态和业务错误码。 */
    public void write(HttpServletResponse response, int status, ErrorCode errorCode) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                ApiResponse.error(errorCode.code(), errorCode.defaultMessage()));
    }
}
