package com.armada.boot.web;

import com.armada.account.controller.DeviceImportController;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** 设备入口专用错误输出；任何异常内容均不进入日志或响应。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = DeviceImportController.class)
public class DeviceImportExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DeviceImportExceptionHandler.class);

    /** 非法 JSON、输入或重复账号必须返回实际失败 HTTP 状态。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handle(Exception error) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String message = "导入服务暂不可用，请稍后重试";
        if (error instanceof BusinessException business) {
            return businessError(business.getCode());
        }
        if (error instanceof HttpMessageNotReadableException) {
            status = HttpStatus.BAD_REQUEST;
            message = "请求字段或 JSON 格式不合法";
        } else if (error instanceof MaxUploadSizeExceededException) {
            status = HttpStatus.PAYLOAD_TOO_LARGE;
            message = "请求内容过大";
        } else if (error instanceof DataAccessException) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
        }
        return response(status, message);
    }

    private ResponseEntity<Map<String, String>> businessError(int code) {
        if (code == ErrorCode.CONFLICT.code()) {
            return response(HttpStatus.CONFLICT, "账号已导入或正在上线，请在控端查看并处理");
        }
        if (code == ErrorCode.VALIDATION.code()) {
            return response(HttpStatus.BAD_REQUEST, "请求参数不合法，请检查后重试");
        }
        if (code == ErrorCode.NOT_FOUND.code()) {
            return response(HttpStatus.BAD_REQUEST, "分组或交接批次不可用，请在控端核对");
        }
        if (code == ErrorCode.DEVICE_IMPORT_GROUP_NOT_FOUND.code()) {
            return response(HttpStatus.NOT_FOUND, "分组不存在");
        }
        if (code == ErrorCode.TENANT_MISSING.code()) {
            return response(HttpStatus.UNAUTHORIZED, "导入令牌无效");
        }
        return response(HttpStatus.SERVICE_UNAVAILABLE, "导入配置不可用，请联系管理员");
    }

    private ResponseEntity<Map<String, String>> response(HttpStatus status, String message) {
        log.warn("device.import.reject code={}", status.value());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(Map.of("message", message));
    }
}
