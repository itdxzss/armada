package com.armada.platform.wbpro;

import com.armada.platform.wbpro.model.WbproModels;
import com.armada.platform.wbpro.model.WbproTarget;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * WB 集成接口的 HTTP 实现。签名串与 WB IntegrationAuthFilter 一致：
 * METHOD \n 原始路径和查询串 \n 毫秒时间戳 \n nonce \n hex(SHA-256(body))，HMAC-SHA256 十六进制小写。
 */
public final class HttpWbproIntegrationClient implements WbproIntegrationClient {

    private static final String API = "/api/integration/v1";
    private static final String GET = "GET";
    private static final String POST = "POST";
    private static final String DELETE = "DELETE";
    private static final int HTTP_OK_MIN = 200;
    private static final int HTTP_OK_MAX = 299;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Duration timeout;

    public HttpWbproIntegrationClient(HttpClient http, ObjectMapper mapper, Duration timeout) {
        this.http = http;
        // WB 响应会带本端不关心的字段（envId、importRef 等），读取时忽略未知字段。
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.timeout = timeout;
    }

    @Override
    public WbproModels.Env env(WbproTarget target) {
        return convert(call(target, GET, API + "/env", null), WbproModels.Env.class);
    }

    @Override
    public Optional<WbproModels.Member> member(WbproTarget target, String username) {
        JsonNode data = call(target, GET, API + "/members?username=" + encode(username), null);
        JsonNode member = data.path("member");
        return member.isObject() ? Optional.of(convert(member, WbproModels.Member.class)) : Optional.empty();
    }

    @Override
    public WbproModels.PairingStarted startPairing(WbproTarget target, WbproPairingCommand command) {
        return convert(call(target, POST, API + "/pairings", command), WbproModels.PairingStarted.class);
    }

    @Override
    public WbproModels.PairingState pairing(WbproTarget target, String pairingId) {
        return convert(call(target, GET, API + "/pairings/" + encode(pairingId), null), WbproModels.PairingState.class);
    }

    @Override
    public List<WbproModels.Device> devices(WbproTarget target, String wsPhone) {
        JsonNode devices = call(target, GET, API + "/devices?phone=" + encode(wsPhone), null).path("devices");
        if (!devices.isArray()) {
            throw new WbproIntegrationException(WbproIntegrationException.BAD_RESPONSE, HTTP_OK_MIN);
        }
        List<WbproModels.Device> out = new ArrayList<>();
        devices.forEach(node -> out.add(convert(node, WbproModels.Device.class)));
        return out;
    }

    @Override
    public void unbind(WbproTarget target, long deviceId) {
        call(target, DELETE, API + "/devices/" + deviceId, null);
    }

    private JsonNode call(WbproTarget target, String method, String pathWithQuery, Object body) {
        byte[] bytes = serialize(body);
        URI uri = URI.create(target.baseUrl().replaceAll("/+$", "") + pathWithQuery);
        String signedPath = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("X-WB-Client", target.clientId())
                .header("X-WB-Timestamp", timestamp)
                .header("X-WB-Nonce", nonce)
                .header("X-WB-Signature", sign(target.secret(), method, signedPath, timestamp, nonce, bytes))
                .header("Content-Type", "application/json")
                .method(method, bytes.length == 0 ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(bytes));
        HttpResponse<byte[]> response = send(request.build());
        JsonNode root = parse(response.body(), response.statusCode());
        if (response.statusCode() < HTTP_OK_MIN || response.statusCode() > HTTP_OK_MAX) {
            String code = root.path("code").asText(WbproIntegrationException.BAD_RESPONSE);
            throw new WbproIntegrationException(code, response.statusCode());
        }
        JsonNode data = root.path("data");
        // 目标环境核对：配置写错域名时宁可失败，也不能把号导到别的 WB 环境。
        if (!target.envId().equals(data.path("envId").asText(null))) {
            throw new WbproIntegrationException(WbproIntegrationException.ENV_MISMATCH, response.statusCode());
        }
        return data;
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException ex) {
            throw new WbproIntegrationException(WbproIntegrationException.UNAVAILABLE, 0);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new WbproIntegrationException(WbproIntegrationException.UNAVAILABLE, 0);
        }
    }

    private JsonNode parse(byte[] body, int status) {
        try {
            return mapper.readTree(body);
        } catch (IOException ex) {
            throw new WbproIntegrationException(WbproIntegrationException.BAD_RESPONSE, status);
        }
    }

    private byte[] serialize(Object body) {
        if (body == null) {
            return new byte[0];
        }
        try {
            return mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("WB 请求序列化失败", ex);
        }
    }

    private <T> T convert(JsonNode node, Class<T> type) {
        try {
            return mapper.treeToValue(node, type);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new WbproIntegrationException(WbproIntegrationException.BAD_RESPONSE, HTTP_OK_MIN);
        }
    }

    /**
     * 计算请求签名，与 WB 的 IntegrationAuthFilter.sign 同一算法。
     *
     * @param secret 接入方密钥
     * @param method HTTP 方法
     * @param pathWithQuery 原始路径和查询串
     * @param timestamp 毫秒时间戳
     * @param nonce 随机串
     * @param body 请求体字节
     * @return 十六进制小写签名
     */
    static String sign(String secret, String method, String pathWithQuery, String timestamp, String nonce, byte[] body) {
        try {
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String payload = method + "\n" + pathWithQuery + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
