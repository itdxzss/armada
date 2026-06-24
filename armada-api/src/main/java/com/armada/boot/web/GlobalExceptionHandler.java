package com.armada.boot.web;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理。把 {@link BusinessException} 转成 {@code ApiResponse.code != 0},
 * 而非裸 RuntimeException 或 HTTP 5xx——可恢复错误以 code 反馈,前端据 code 判定成败。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final int INTERNAL_ERROR_CODE = 50000;

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusiness(BusinessException ex) {
        log.warn("业务异常 code={} msg={}", ex.getCode(), ex.getMessage());
        return ApiResponse.error(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpected(Exception ex) {
        log.error("未预期异常", ex);
        return ApiResponse.error(INTERNAL_ERROR_CODE, "系统繁忙,请稍后重试");
    }
}
