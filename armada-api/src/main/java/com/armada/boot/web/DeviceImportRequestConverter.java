package com.armada.boot.web;

import com.armada.account.model.dto.DeviceImportDTO;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** 仅对设备 DTO 有效的严格 JSON 读取器；读取流时限额，不缓存到文件，不附带解析原文异常。 */
public final class DeviceImportRequestConverter extends AbstractHttpMessageConverter<DeviceImportDTO> {

    /** 冻结请求上限，与专用 nginx 配置一致。 */
    public static final int MAX_BODY_BYTES = 128 * 1024;
    private final ObjectReader reader = new ObjectMapper().reader()
            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** 只接收 JSON，不改变其他接口的 Jackson 策略。 */
    public DeviceImportRequestConverter() {
        super(MediaType.APPLICATION_JSON);
    }

    @Override
    protected boolean supports(Class<?> type) {
        return type == DeviceImportDTO.class;
    }

    @Override
    protected DeviceImportDTO readInternal(Class<? extends DeviceImportDTO> type, HttpInputMessage input) {
        try {
            byte[] body = input.getBody().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw new MaxUploadSizeExceededException(MAX_BODY_BYTES);
            }
            JsonNode root = reader.readTree(body);
            if (root == null || !root.isObject() || root.size() != 2
                    || !root.path("phone").isTextual() || !root.path("payload").isTextual()) {
                throw new HttpMessageNotReadableException("请求必须包含 phone 和 payload 字符串", input);
            }
            return new DeviceImportDTO(root.path("phone").textValue(), root.path("payload").textValue());
        } catch (IOException ex) {
            throw new HttpMessageNotReadableException("请求 JSON 格式不正确", input);
        }
    }

    /** 敏感入参没有响应序列化路径。 */
    @Override
    public boolean canWrite(Class<?> type, MediaType mediaType) {
        return false;
    }

    @Override
    protected void writeInternal(DeviceImportDTO request, HttpOutputMessage output) {
        throw new UnsupportedOperationException("设备凭据不能作为响应输出");
    }
}
