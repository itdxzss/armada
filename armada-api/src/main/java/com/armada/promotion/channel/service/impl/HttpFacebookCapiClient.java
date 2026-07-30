package com.armada.promotion.channel.service.impl;

import com.armada.promotion.channel.service.FacebookCapiClient;
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

/** 使用 Spring RestClient 调用 Meta Graph API，并把错误映射为稳定脱敏结果。 */
@Component
public class HttpFacebookCapiClient implements FacebookCapiClient {

    private static final Pattern PIXEL_ID_PATTERN = Pattern.compile("^[0-9]{5,32}$");
    private static final Pattern API_VERSION_PATTERN = Pattern.compile("^v[0-9]{1,2}\\.[0-9]$");
    private static final String META_GRAPH_HOST = "graph.facebook.com";
    private static final int MAX_TIMEOUT_MILLIS = 30_000;
    private static final int MAX_TOTAL_TIMEOUT_MILLIS = 45_000;

    private final RestClient restClient;
    private final String apiVersion;

    @Autowired
    public HttpFacebookCapiClient(
            RestClient.Builder builder,
            @Value("${armada.promotion.tracking.facebook.base-url:https://graph.facebook.com}") String baseUrl,
            @Value("${armada.promotion.tracking.facebook.api-version:v22.0}") String apiVersion,
            @Value("${armada.promotion.tracking.facebook.connect-timeout-ms:5000}") int connectTimeoutMillis,
            @Value("${armada.promotion.tracking.facebook.read-timeout-ms:10000}") int readTimeoutMillis) {
        this(builder, baseUrl, apiVersion, connectTimeoutMillis, readTimeoutMillis, false);
    }

    HttpFacebookCapiClient(
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
        this.restClient = builder.baseUrl(normalizedBaseUrl)
                .requestFactory(requestFactory)
                .build();
        this.apiVersion = apiVersion;
    }

    @Override
    public Result probe(ProbeCommand command) {
        Result invalid = validateCredentials(command == null ? null : command.trackingId(),
                command == null ? null : command.accessToken());
        if (invalid != null) return invalid;
        return post(command.trackingId(), command.accessToken(), probeBody(command), true);
    }

    @Override
    public Result send(BusinessEventCommand command) {
        Result invalid = validateCredentials(command == null ? null : command.trackingId(),
                command == null ? null : command.accessToken());
        if (invalid != null) return invalid;
        return post(command.trackingId(), command.accessToken(), businessBody(command), false);
    }

    private Result post(String trackingId, String accessToken, Map<String, Object> body, boolean probe) {
        try {
            FacebookResponse response = restClient.post()
                    .uri("/{apiVersion}/{pixelId}/events", apiVersion, trackingId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(FacebookResponse.class);
            if (response != null && response.eventsReceived() != null && response.eventsReceived() > 0) {
                return Result.accepted();
            }
            return new Result(false, false, "EVENT_REJECTED",
                    probe ? "Facebook 未接收测试事件" : "Facebook 未接收业务事件");
        } catch (RestClientResponseException ex) {
            return mapHttpError(ex.getStatusCode().value(), probe);
        } catch (ResourceAccessException ex) {
            return new Result(false, true, "NETWORK_TIMEOUT", "连接 Facebook 超时或网络不可达");
        } catch (RestClientException ex) {
            return new Result(false, true, "FACEBOOK_REQUEST_FAILED", "Facebook CAPI 请求执行失败");
        }
    }

    private static Map<String, Object> probeBody(ProbeCommand command) {
        Map<String, Object> event = baseEvent(
                command.eventName(), command.eventTimeSeconds(), command.eventId(),
                command.eventSourceUrl(), Map.of("external_id", List.of(command.externalId())));
        return Map.of("data", List.of(event), "test_event_code", command.testEventCode());
    }

    private static Map<String, Object> businessBody(BusinessEventCommand command) {
        Map<String, Object> userData = new LinkedHashMap<>();
        putIfText(userData, "ph", command.phoneSha256() == null
                ? null : List.of(command.phoneSha256()));
        putIfText(userData, "client_ip_address", command.clientIp());
        putIfText(userData, "client_user_agent", command.clientUserAgent());
        putIfText(userData, "fbp", command.fbp());
        putIfText(userData, "fbc", command.fbc());
        return Map.of("data", List.of(baseEvent(
                command.eventName(), command.eventTimeSeconds(), command.eventId(),
                command.eventSourceUrl(), userData)));
    }

    private static Map<String, Object> baseEvent(
            String eventName, long eventTime, String eventId,
            String eventSourceUrl, Map<String, Object> userData) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_name", eventName);
        event.put("event_time", eventTime);
        event.put("event_id", eventId);
        event.put("action_source", "website");
        if (StringUtils.hasText(eventSourceUrl)) {
            event.put("event_source_url", eventSourceUrl);
        }
        event.put("user_data", userData);
        return event;
    }

    private static void putIfText(Map<String, Object> target, String key, Object value) {
        if (value instanceof String text && StringUtils.hasText(text)) {
            target.put(key, text);
        } else if (value instanceof List<?> list && !list.isEmpty()) {
            target.put(key, list);
        }
    }

    private static Result validateCredentials(String trackingId, String accessToken) {
        if (!StringUtils.hasText(trackingId) || !PIXEL_ID_PATTERN.matcher(trackingId).matches()) {
            return new Result(false, false, "INVALID_PIXEL_ID", "Facebook Pixel ID 格式不正确");
        }
        if (!isSafeHeaderValue(accessToken)) {
            return new Result(false, false, "INVALID_ACCESS_TOKEN", "Access Token 格式不正确");
        }
        return null;
    }

    private static Result mapHttpError(int status, boolean probe) {
        return switch (status) {
            case 401, 403 -> new Result(false, false,
                    "TOKEN_INVALID_OR_FORBIDDEN", "Access Token 无效或无 Pixel 权限");
            case 404 -> new Result(false, false,
                    "PIXEL_NOT_FOUND", "Facebook Pixel 不存在或不可访问");
            case 429 -> new Result(false, true,
                    "RATE_LIMITED", "Facebook 调用频率受限，请稍后重试");
            default -> status >= 500
                    ? new Result(false, true, "FACEBOOK_UNAVAILABLE", "Facebook 服务暂时不可用")
                    : new Result(false, false, "EVENT_REJECTED",
                            probe ? "Facebook 拒绝测试事件，请检查 Pixel 和测试码"
                                    : "Facebook 拒绝业务事件，请检查 Pixel 和事件配置");
        };
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

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

    private static boolean isSafeHeaderValue(String value) {
        return StringUtils.hasText(value)
                && value.chars().noneMatch(ch -> ch <= 31 || ch == 127);
    }

    private record FacebookResponse(@JsonProperty("events_received") Integer eventsReceived) {
    }
}
