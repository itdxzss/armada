package com.armada.promotion.channel.service.impl;

import com.armada.promotion.channel.service.FacebookCapiProbeClient;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** 使用 Spring RestClient 调用 Meta Graph API；所有错误都转换为稳定的脱敏探测结果。 */
@Component
public class HttpFacebookCapiProbeClient implements FacebookCapiProbeClient {

    private static final Pattern PIXEL_ID_PATTERN = Pattern.compile("^[0-9]{5,32}$");
    private static final Pattern API_VERSION_PATTERN = Pattern.compile("^v[0-9]{1,2}\\.[0-9]$");
    private static final String META_GRAPH_HOST = "graph.facebook.com";
    private static final int MAX_TIMEOUT_MILLIS = 30_000;
    private static final int MAX_TOTAL_TIMEOUT_MILLIS = 45_000;

    private final RestClient restClient;
    private final String apiVersion;

    /**
     * 构造模块私有的 Facebook HTTP 客户端。
     *
     * @param builder Spring Boot 管理的 RestClient 构造器
     * @param baseUrl Meta Graph API 根地址；默认指向官方 HTTPS 地址
     * @param apiVersion Meta Graph API 版本
     * @param connectTimeoutMillis 连接超时毫秒数
     * @param readTimeoutMillis 读取超时毫秒数
     */
    @Autowired
    public HttpFacebookCapiProbeClient(
            RestClient.Builder builder,
            @Value("${armada.promotion.tracking.facebook.base-url:https://graph.facebook.com}") String baseUrl,
            @Value("${armada.promotion.tracking.facebook.api-version:v22.0}") String apiVersion,
            @Value("${armada.promotion.tracking.facebook.connect-timeout-ms:5000}") int connectTimeoutMillis,
            @Value("${armada.promotion.tracking.facebook.read-timeout-ms:10000}") int readTimeoutMillis) {
        this(builder, baseUrl, apiVersion, connectTimeoutMillis, readTimeoutMillis, false);
    }

    /** 仅供包内 HTTP 适配器测试注入本地服务器；Spring 生产构造器始终使用严格模式。 */
    HttpFacebookCapiProbeClient(
            RestClient.Builder builder,
            String baseUrl,
            String apiVersion,
            int connectTimeoutMillis,
            int readTimeoutMillis,
            boolean allowInsecureTestEndpoint) {
        if (!StringUtils.hasText(baseUrl) || !API_VERSION_PATTERN.matcher(apiVersion).matches()) {
            throw new IllegalArgumentException("Facebook Graph API 地址或版本配置无效");
        }
        if (connectTimeoutMillis <= 0 || connectTimeoutMillis > MAX_TIMEOUT_MILLIS
                || readTimeoutMillis <= 0 || readTimeoutMillis > MAX_TIMEOUT_MILLIS
                || connectTimeoutMillis + readTimeoutMillis > MAX_TOTAL_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("Facebook Graph API 单项超时需在1到30000毫秒且总和不超过45000毫秒");
        }
        String normalizedBaseUrl = stripTrailingSlash(baseUrl.trim());
        if (!allowInsecureTestEndpoint && !isOfficialMetaGraphEndpoint(normalizedBaseUrl)) {
            throw new IllegalArgumentException("Facebook Graph API 生产地址必须是官方 HTTPS 根地址");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMillis);
        requestFactory.setReadTimeout(readTimeoutMillis);
        this.restClient = builder
                .baseUrl(normalizedBaseUrl)
                .requestFactory(requestFactory)
                .build();
        this.apiVersion = apiVersion;
    }

    /** {@inheritDoc} */
    @Override
    public Result probe(Command command) {
        if (command == null || !PIXEL_ID_PATTERN.matcher(command.trackingId()).matches()) {
            return new Result(false, "INVALID_PIXEL_ID", "Facebook Pixel ID 格式不正确");
        }
        if (!isSafeHeaderValue(command.accessToken())) {
            return new Result(false, "INVALID_ACCESS_TOKEN", "Access Token 格式不正确");
        }
        try {
            FacebookResponse response = restClient.post()
                    .uri("/{apiVersion}/{pixelId}/events", apiVersion, command.trackingId())
                    // Token 只放 Authorization Header，避免进入 URL、代理访问日志或异常文本。
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + command.accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(command))
                    .retrieve()
                    .body(FacebookResponse.class);
            if (response != null && response.eventsReceived() != null && response.eventsReceived() > 0) {
                return new Result(true, null, null);
            }
            return new Result(false, "EVENT_REJECTED", "Facebook 未接收测试事件");
        } catch (RestClientResponseException ex) {
            return mapHttpError(ex.getStatusCode().value());
        } catch (ResourceAccessException ex) {
            return new Result(false, "NETWORK_TIMEOUT", "连接 Facebook 超时或网络不可达");
        } catch (RestClientException ex) {
            return new Result(false, "FACEBOOK_REQUEST_FAILED", "Facebook CAPI 请求执行失败");
        }
    }

    /** 构造不含真实用户 PII 的 Meta 测试事件请求。 */
    private static Map<String, Object> requestBody(Command command) {
        Map<String, Object> userData = Map.of(
                "external_id", List.of(command.externalId()));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_name", command.eventName());
        event.put("event_time", command.eventTimeSeconds());
        event.put("event_id", command.eventId());
        event.put("action_source", "website");
        event.put("event_source_url", command.eventSourceUrl());
        event.put("user_data", userData);
        return Map.of(
                "data", List.of(event),
                "test_event_code", command.testEventCode());
    }

    /** 只按 HTTP 状态生成稳定错误，不读取或回显平台原始响应体。 */
    private static Result mapHttpError(int status) {
        return switch (status) {
            case 401, 403 -> new Result(
                    false, "TOKEN_INVALID_OR_FORBIDDEN", "Access Token 无效或无 Pixel 权限");
            case 404 -> new Result(false, "PIXEL_NOT_FOUND", "Facebook Pixel 不存在或不可访问");
            case 429 -> new Result(false, "RATE_LIMITED", "Facebook 调用频率受限，请稍后重试");
            default -> status >= 500
                    ? new Result(false, "FACEBOOK_UNAVAILABLE", "Facebook 服务暂时不可用")
                    : new Result(false, "EVENT_REJECTED", "Facebook 拒绝测试事件，请检查 Pixel 和测试码");
        };
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** 生产配置只允许 Meta Graph 官方 HTTPS 根地址，防止 Token 被配置型 SSRF 外送。 */
    private static boolean isOfficialMetaGraphEndpoint(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && META_GRAPH_HOST.equalsIgnoreCase(uri.getHost())
                    && uri.getPort() == -1
                    && uri.getUserInfo() == null
                    && (uri.getPath() == null || uri.getPath().isEmpty())
                    && uri.getQuery() == null
                    && uri.getFragment() == null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** 拒绝空 Token 和 CR/LF/控制字符，避免 Authorization Header 注入及异常污染。 */
    private static boolean isSafeHeaderValue(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        return value.chars().noneMatch(ch -> ch <= 31 || ch == 127);
    }

    /** Meta CAPI 成功响应；只解析接收数量，忽略 messages 和 trace，避免扩大数据面。 */
    private record FacebookResponse(@JsonProperty("events_received") Integer eventsReceived) {
    }
}
