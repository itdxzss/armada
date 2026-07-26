package com.armada.boot.security;

import com.armada.shared.exception.ErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/** 缺少或失效 Token 的统一 401 响应。 */
@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityJsonWriter writer;

    public JsonAuthenticationEntryPoint(SecurityJsonWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {
        writer.write(response, HttpStatus.UNAUTHORIZED.value(), ErrorCode.AUTH_INVALID);
    }
}
