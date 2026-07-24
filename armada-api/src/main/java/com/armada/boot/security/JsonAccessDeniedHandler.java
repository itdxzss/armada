package com.armada.boot.security;

import com.armada.shared.exception.ErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** 已登录但权限不足时的统一 403 响应。 */
@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityJsonWriter writer;

    public JsonAccessDeniedHandler(SecurityJsonWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception) throws IOException, ServletException {
        writer.write(response, HttpStatus.FORBIDDEN.value(), ErrorCode.ACCESS_DENIED);
    }
}
