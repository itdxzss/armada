package com.armada.platform.registration.go;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Go 注册三接口的 HTTP 适配器：只翻译请求与响应，不做重试、不做业务决策。
 *
 * <p>每个方法只发一次 HTTP 请求。幂等靠调用方传入的稳定 Idempotency-Key：同键同参重放时 Go 返回原操作的结果，
 * 所以超时、重启后由调用方原样再调一次即可，这里不自行重试。号码、验证码、代理和凭据不写日志，
 * 原始传输异常也不保留（可能带出代理账号密码）。</p>
 */
public final class GoRegistrationClient {

    /** Go 注册接口根路径。 */
    private static final String BASE_PATH = "/ws/v1/registrations";
    /** 幂等键请求头，Go 要求 POST 必带。 */
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    /** Armada 确认的租户归属；Go 注册接口必须限制为内部服务访问。 */
    private static final String TENANT_HEADER = "X-Tenant-Id";
    /** 响应体上限，防止异常服务端耗尽内存；合法响应只有几百字节。 */
    private static final int MAX_RESPONSE_BYTES = 65_536;
    /** Go 事务 ID 是 UUID；放进路径前校验，防止路径注入。 */
    private static final Pattern TRANSACTION_ID = Pattern.compile("[0-9A-Za-z-]{1,64}");
    /** Go 信封里的成功业务码。 */
    private static final int SUCCESS_CODE = 0;
    /** 202 且 error_code 为该值表示 Go 仍在执行原操作。 */
    private static final String IN_PROGRESS = "OPERATION_IN_PROGRESS";

    /** 验码请求体。 */
    private record VerifyBody(String code, @JsonProperty("expected_version") long expectedVersion) { }

    /** 专用、禁止重试和重定向的 HTTP 客户端。 */
    private final RestClient restClient;
    /** 解析 Go 的 Code/Data/Msg 信封。 */
    private final ObjectMapper objectMapper;
    /**
     * @param restClient 专用 HTTP 客户端，装配层必须禁止自动重试和重定向
     * @param objectMapper 项目 JSON 解析器
     */
    public GoRegistrationClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 首次申请验证码（POST code-requests）。
     * @param tenantId 租户，必须来自登录上下文或已持久化任务
     * @param idempotencyKey 稳定幂等键，重放必须与首次完全相同
     * @param request 开始时冻结的号码、配置、代理与账号类型，禁止写日志
     * @return 分类后的结果
     * @throws BusinessException 租户无效时抛出
     */
    public GoRegistrationResponse requestCode(Long tenantId, String idempotencyKey, GoRegistrationRequest request) {
        return post(tenantId, BASE_PATH + "/code-requests", idempotencyKey, request);
    }

    /**
     * 提交验证码（POST code-verifications）。
     * @param tenantId 租户
     * @param idempotencyKey 稳定幂等键
     * @param transactionId 发码成功时拿到的 Go 事务 ID
     * @param code 验证码字符串，保留前导零，不 trim 不转整数
     * @param expectedVersion 发码成功时拿到的 version，重放必须不变
     * @return 分类后的结果
     * @throws BusinessException 租户无效或事务 ID 非法时抛出
     */
    public GoRegistrationResponse verifyCode(Long tenantId, String idempotencyKey, String transactionId, String code,
            long expectedVersion) {
        requireTransactionId(transactionId);
        return post(tenantId, BASE_PATH + "/" + transactionId + "/code-verifications", idempotencyKey,
                new VerifyBody(code, expectedVersion));
    }

    /**
     * 只读查询事务状态（GET），不触发任何外部操作。
     * @param tenantId 租户
     * @param transactionId Go 事务 ID
     * @return 分类后的结果
     * @throws BusinessException 租户无效或事务 ID 非法时抛出
     */
    public GoRegistrationResponse status(Long tenantId, String transactionId) {
        requireTransactionId(transactionId);
        String tenantHeader = requireTenant(tenantId);
        try {
            return restClient.get().uri(BASE_PATH + "/" + transactionId)
                    .header(TENANT_HEADER, tenantHeader).exchange(this::read);
        } catch (RestClientException exception) {
            return GoRegistrationResponse.of(GoRegistrationResponse.Kind.NO_RESPONSE);
        }
    }

    private GoRegistrationResponse post(Long tenantId, String path, String idempotencyKey, Object body) {
        String tenantHeader = requireTenant(tenantId);
        try {
            return restClient.post().uri(path)
                    .header(TENANT_HEADER, tenantHeader)
                    .header(IDEMPOTENCY_HEADER, idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange(this::read);
        } catch (RestClientException exception) {
            // 原始异常可能带出代理账号密码，不保留 cause；调用方用同一把键重放是安全的。
            return GoRegistrationResponse.of(GoRegistrationResponse.Kind.NO_RESPONSE);
        }
    }

    private String requireTenant(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "租户 ID 无效");
        }
        return tenantId.toString();
    }

    private static void requireTransactionId(String transactionId) {
        if (transactionId == null || !TRANSACTION_ID.matcher(transactionId).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION, "Go 事务 ID 无效");
        }
    }

    private GoRegistrationResponse read(HttpRequest request, ClientHttpResponse response) throws IOException {
        int http = response.getStatusCode().value();
        byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
        Optional<JsonNode> root = bytes.length > MAX_RESPONSE_BYTES ? Optional.empty() : parse(bytes);
        GoRegistrationSnapshot snapshot = root.map(GoRegistrationClient::snapshot)
                .orElseGet(GoRegistrationSnapshot::empty);
        return new GoRegistrationResponse(classify(http, root, snapshot), snapshot);
    }

    private Optional<JsonNode> parse(byte[] bytes) {
        try {
            return Optional.ofNullable(objectMapper.readTree(bytes)).filter(JsonNode::isObject);
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static GoRegistrationSnapshot snapshot(JsonNode root) {
        JsonNode data = root.path("Data");
        return new GoRegistrationSnapshot(data.path("transaction_id").asText(""),
                GoRegistrationState.fromWire(data.path("state").asText("")), data.path("version").asLong(0L),
                data.path("result_available").asBoolean(false), data.path("error_code").asText(""));
    }

    /**
     * 把 HTTP 状态与信封归为调用方能直接决策的分类。
     * 无法确认结果的成功响应一律归为“结果不明”，宁可停下也不当作成功。
     */
    private static GoRegistrationResponse.Kind classify(int http, Optional<JsonNode> root,
            GoRegistrationSnapshot snapshot) {
        if (http == HttpStatus.OK.value()) {
            boolean success = root.map(node -> node.path("Code").asInt(-1) == SUCCESS_CODE).orElse(false);
            return success ? GoRegistrationResponse.Kind.DONE : GoRegistrationResponse.Kind.OUTCOME_UNKNOWN;
        }
        if (http == HttpStatus.ACCEPTED.value()) {
            return IN_PROGRESS.equals(snapshot.errorCode()) ? GoRegistrationResponse.Kind.IN_PROGRESS
                    : GoRegistrationResponse.Kind.OUTCOME_UNKNOWN;
        }
        if (http == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return GoRegistrationResponse.Kind.RATE_LIMITED;
        }
        if (http == HttpStatus.CONFLICT.value()) {
            return GoRegistrationResponse.Kind.CONFLICT;
        }
        if (http == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            return GoRegistrationResponse.Kind.UNAVAILABLE;
        }
        // 其余 5xx（含 Go 持久化错误、网关超时）没有给出明确业务结果，同键重放是安全的。
        return http >= HttpStatus.INTERNAL_SERVER_ERROR.value() ? GoRegistrationResponse.Kind.NO_RESPONSE
                : GoRegistrationResponse.Kind.REJECTED;
    }
}
