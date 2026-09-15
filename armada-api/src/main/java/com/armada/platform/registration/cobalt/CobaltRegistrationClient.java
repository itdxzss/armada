package com.armada.platform.registration.cobalt;

import com.armada.platform.registration.cobalt.model.CobaltHealth;
import com.armada.platform.registration.cobalt.model.CobaltRegistrationSnapshot;
import com.armada.platform.registration.cobalt.model.CobaltRegistrationState;
import com.armada.platform.registration.cobalt.model.CobaltSixCredential;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Cobalt 内部 HTTP 适配；稳定会话 ID 贯穿注册、验证码提交和凭据导出。 */
public final class CobaltRegistrationClient {
    /** 可直接作为单个 URL 段的幂等会话 ID。 */ private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,96}");
    /** 国际区号加号码。 */ private static final Pattern PHONE = Pattern.compile("[1-9][0-9]{6,14}");
    /** 当前 WhatsApp 短信验证码合同。 */ private static final Pattern CODE = Pattern.compile("[0-9]{6}");
    /** 稳定错误分类，不接收含空格的错误正文。 */ private static final Pattern REASON = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    /** 限制服务响应大小。 */ private static final int MAX_RESPONSE_BYTES = 65_536;
    /** 固定内部 API 前缀。 */ private static final String REGISTRATIONS = "/v1/registrations";
    /** 明确的 Zhuan 适配版本，不接受 Cobalt 默认六段格式。 */ private static final String SIX_FORMAT = "zhuan-six-v1";
    /** 专用 HTTP transport。 */ private final RestClient restClient;
    /** 严格读取 JSON，禁止额外尾部数据和重复字段。 */ private final ObjectReader reader;
    /** 内部密钥与启用配置。 */ private final CobaltRegistrationProperties properties;

    /** @param restClient 专用 HTTP 客户端 @param mapper 项目 JSON 配置 @param properties 服务端内部配置 */
    public CobaltRegistrationClient(RestClient restClient, ObjectMapper mapper, CobaltRegistrationProperties properties) {
        this.restClient = restClient;
        this.reader = mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.properties = properties;
    }

    /** @return 本机是否启用并配置了内部密钥，不发起网络请求 */
    public boolean isEnabled() { return properties.isEnabled() && properties.hasValidToken(); }

    /** @return 服务端真实注册开关与当前容量；容量只是查询时的快照 */
    public CobaltHealth health() {
        JsonNode node = request(HttpMethod.GET, "/v1/health", Optional.empty());
        if (!node.path("registrationEnabled").isBoolean() || !node.path("availableSlots").canConvertToInt()
                || !node.path("availableSlots").isIntegralNumber() || node.path("availableSlots").intValue() < 0) {
            throw invalid(false);
        }
        return new CobaltHealth(node.get("registrationEnabled").booleanValue(), node.get("availableSlots").intValue());
    }

    /** @param id 稳定幂等 ID @param phoneNumber 已采购号码 @param accountType 个人 1 或商业 2 @return 原会话快照 */
    public CobaltRegistrationSnapshot create(String id, String phoneNumber, int accountType) {
        require(id, ID, "注册会话 ID 无效");
        require(phoneNumber, PHONE, "注册号码无效");
        if (accountType != 1 && accountType != 2) throw new BusinessException(ErrorCode.VALIDATION, "注册账号类型无效");
        var result = snapshot(request(HttpMethod.POST, REGISTRATIONS,
                Optional.of(new CreateRequest(id, phoneNumber, accountType))), id, true);
        if (!phoneNumber.equals(result.phoneNumber())) throw invalid(true);
        return result;
    }

    /** @param id 已持久化的会话 ID @return 真实注册阶段，不将受理当注册成功 */
    public CobaltRegistrationSnapshot status(String id) {
        return snapshot(request(HttpMethod.GET, sessionPath(id), Optional.empty()), id, false);
    }

    /** @param id 原会话 ID @param code 实际收到的六位短信验证码 @return 提交后的阶段 */
    public CobaltRegistrationSnapshot submitCode(String id, String code) {
        require(code, CODE, "验证码格式无效");
        return snapshot(request(HttpMethod.POST, sessionPath(id) + "/code", Optional.of(new CodeRequest(code))), id, true);
    }

    /** @param id 注册已确认的原会话 ID @return 经六字段格式校验的敏感凭据，只供服务端导入 */
    public CobaltSixCredential exportSix(String id) {
        JsonNode node = request(HttpMethod.POST, sessionPath(id) + "/credentials", Optional.empty());
        String phone = field(node, "phoneNumber", false);
        String six = field(node, "sixLine", false);
        if (!id.equals(field(node, "registrationId", false)) || !SIX_FORMAT.equals(field(node, "format", false))
                || !PHONE.matcher(phone).matches()) throw invalid(false);
        validateSix(six, phone);
        return new CobaltSixCredential(id, phone, SIX_FORMAT, six);
    }

    private void validateSix(String line, String phone) {
        String[] fields = line.split(",", -1);
        if (fields.length != 6 || !phone.equals(fields[0])) throw invalid(false);
        try {
            for (int i = 1; i <= 4; i++) {
                byte[] bytes = Base64.getDecoder().decode(fields[i]);
                if (bytes.length != 32 || !Base64.getEncoder().encodeToString(bytes).equals(fields[i])) throw invalid(false);
            }
            if (!UUID.fromString(fields[5]).toString().equalsIgnoreCase(fields[5])) throw invalid(false);
        } catch (IllegalArgumentException error) {
            throw invalid(false);
        }
    }

    private CobaltRegistrationSnapshot snapshot(JsonNode node, String id, boolean mutation) {
        String phone = field(node, "phoneNumber", mutation);
        if (!id.equals(field(node, "registrationId", mutation)) || !PHONE.matcher(phone).matches()) throw invalid(mutation);
        try {
            var state = CobaltRegistrationState.valueOf(field(node, "state", mutation));
            String reason = node.path("reason").asText("");
            if (!reason.isEmpty() && !REASON.matcher(reason).matches()) throw invalid(mutation);
            return new CobaltRegistrationSnapshot(id, phone, state, reason.isEmpty() ? Optional.empty() : Optional.of(reason));
        } catch (IllegalArgumentException error) {
            throw invalid(mutation);
        }
    }

    private JsonNode request(HttpMethod method, String path, Optional<Object> body) {
        if (!isEnabled()) throw new CobaltRegistrationException(CobaltRegistrationFailure.DISABLED, 0, false);
        boolean mutation = method == HttpMethod.POST && !path.endsWith("/credentials");
        try {
            var spec = restClient.method(method).uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiToken())
                    .accept(MediaType.APPLICATION_JSON);
            body.ifPresent(value -> spec.contentType(MediaType.APPLICATION_JSON).body(value));
            return spec.exchange((req, response) -> {
                int status = response.getStatusCode().value();
                if (!response.getStatusCode().is2xxSuccessful()) throw httpError(status, mutation);
                byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) throw invalid(mutation);
                return parse(new String(bytes, StandardCharsets.UTF_8), mutation);
            });
        } catch (RestClientException error) {
            // 原始 cause 可能包含号码、验证码、密钥或响应正文，不保留、不记录。
            throw new CobaltRegistrationException(CobaltRegistrationFailure.TRANSPORT, 0, mutation);
        }
    }

    private JsonNode parse(String body, boolean mutation) {
        try {
            JsonNode result = reader.readTree(body);
            if (result == null || !result.isObject()) throw invalid(mutation);
            return result;
        } catch (JsonProcessingException error) {
            throw invalid(mutation);
        }
    }

    private CobaltRegistrationException httpError(int status, boolean mutation) {
        CobaltRegistrationFailure reason = switch (status) {
            case 404 -> CobaltRegistrationFailure.NOT_FOUND;
            case 409, 429 -> CobaltRegistrationFailure.BUSY;
            default -> CobaltRegistrationFailure.REJECTED;
        };
        return new CobaltRegistrationException(reason, status, mutation && status >= 500);
    }

    private String sessionPath(String id) {
        require(id, ID, "注册会话 ID 无效");
        return REGISTRATIONS + "/" + id;
    }
    private String field(JsonNode node, String name, boolean mutation) {
        if (!node.path(name).isTextual() || node.get(name).textValue().isBlank()) throw invalid(mutation);
        return node.get(name).textValue();
    }
    private void require(String value, Pattern pattern, String message) {
        if (value == null || !pattern.matcher(value).matches()) throw new BusinessException(ErrorCode.VALIDATION, message);
    }
    private CobaltRegistrationException invalid(boolean mutation) {
        return new CobaltRegistrationException(CobaltRegistrationFailure.INVALID_RESPONSE, 0, mutation);
    }
    /** HTTP converter 的调试输出也不展开敏感请求字段。 */
    private record CreateRequest(String registrationId, String phoneNumber, int accountType) {
        @Override public String toString() { return "CobaltCreateRequest[REDACTED]"; }
    }
    /** 验证码只在 HTTP 请求体内短暂传递。 */
    private record CodeRequest(String code) {
        @Override public String toString() { return "CobaltCodeRequest[REDACTED]"; }
    }
}
