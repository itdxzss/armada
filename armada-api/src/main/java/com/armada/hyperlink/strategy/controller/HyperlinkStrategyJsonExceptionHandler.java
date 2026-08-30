package com.armada.hyperlink.strategy.controller;

import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.ApiResponse;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 仅收紧超链策略请求 JSON，不改变其他接口的全局 Jackson 兼容策略。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = HyperlinkStrategyController.class)
public class HyperlinkStrategyJsonExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> handleUnreadable(HttpMessageNotReadableException exception) {
        String message = unknownField(exception)
                .map(unknown -> "accountFilter 未知字段: " + unknown.field())
                .orElse("超链策略请求 JSON 非法");
        return ApiResponse.error(ErrorCode.VALIDATION.code(), message);
    }

    private Optional<HyperlinkAccountFilterDTO.UnknownFieldException> unknownField(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof HyperlinkAccountFilterDTO.UnknownFieldException unknown) {
                return Optional.of(unknown);
            }
            current = current.getCause();
        }
        return Optional.empty();
    }
}
