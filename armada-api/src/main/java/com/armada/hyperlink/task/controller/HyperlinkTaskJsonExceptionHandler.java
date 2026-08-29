package com.armada.hyperlink.task.controller;

import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 仅收紧超链任务请求 JSON，不改变其他接口的全局 Jackson 兼容策略。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = HyperlinkTaskController.class)
public class HyperlinkTaskJsonExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> handleUnreadable(HttpMessageNotReadableException exception) {
        HyperlinkAccountFilterDTO.UnknownFieldException unknown = unknownField(exception);
        String message = unknown == null
                ? "超链任务请求 JSON 非法"
                : "accountFilter 未知字段: " + unknown.field();
        return ApiResponse.error(ErrorCode.VALIDATION.code(), message);
    }

    private HyperlinkAccountFilterDTO.UnknownFieldException unknownField(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof HyperlinkAccountFilterDTO.UnknownFieldException unknown) {
                return unknown;
            }
            current = current.getCause();
        }
        return null;
    }
}
