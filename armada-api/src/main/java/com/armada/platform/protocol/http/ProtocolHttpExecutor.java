package com.armada.platform.protocol.http;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 协议层 HTTP 调用统一执行器。
 *
 * <p>本类只负责通用 HTTP 执行、2xx 响应反序列化、非 2xx/网络异常到
 * {@link ProtocolException} 的映射。具体账号、群组、消息等 adapter 在后续小口中基于本类实现。</p>
 */
public class ProtocolHttpExecutor {

    /**
     * 协议层 API key 请求头名。
     */
    public static final String API_KEY_HEADER = "x-api-key";

    /**
     * JSON 预览最大长度,避免异常消息携带巨型响应体。
     */
    private static final int BODY_PREVIEW_MAX_CHARS = 200;

    /**
     * 无 body POST 统一发送空 JSON 对象,避免 Fastify 空 JSON body 400。
     */
    private static final Map<String, Object> EMPTY_JSON_BODY = Map.of();

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final RestClient restClient;

    /**
     * 创建协议层 HTTP 执行器。
     *
     * @param restClient 已配置 baseUrl、超时和鉴权头的 RestClient
     */
    public ProtocolHttpExecutor(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 发起 GET 请求并反序列化 2xx 响应体。
     *
     * @param uri          相对或绝对 URI
     * @param responseType 响应类型
     * @return 反序列化后的响应对象
     * @throws ProtocolException 协议层返回非 2xx、网络异常或响应体不可解析时抛出
     */
    public <T> T getTyped(String uri, Class<T> responseType) {
        return execute("GET", uri, () -> restClient.get().uri(uri)
                .exchange((request, response) -> readTyped(request.getURI(), response, responseType), true));
    }

    /**
     * 发起 POST 请求并反序列化 2xx 响应体。
     *
     * @param uri          相对或绝对 URI
     * @param body         请求体;为空时发送空 JSON 对象
     * @param responseType 响应类型
     * @return 反序列化后的响应对象
     * @throws ProtocolException 协议层返回非 2xx、网络异常或响应体不可解析时抛出
     */
    public <T> T postTyped(String uri, Object body, Class<T> responseType) {
        return execute("POST", uri, () -> restClient.post().uri(uri)
                .body(body == null ? EMPTY_JSON_BODY : body)
                .exchange((request, response) -> readTyped(request.getURI(), response, responseType), true));
    }

    /**
     * 发起 POST 请求并只校验 2xx 成功。
     *
     * @param uri  相对或绝对 URI
     * @param body 请求体;为空时发送空 JSON 对象
     * @throws ProtocolException 协议层返回非 2xx 或网络异常时抛出
     */
    public void postVoid(String uri, Object body) {
        execute("POST", uri, () -> restClient.post().uri(uri)
                .body(body == null ? EMPTY_JSON_BODY : body)
                .exchange((request, response) -> {
                    ensureSuccess(request.getURI(), response);
                    return null;
                }, true));
    }

    /**
     * 执行一次 RestClient 调用,并把底层异常统一翻译成 {@link ProtocolException}。
     *
     * <p>GET/POST 的具体写法由调用方通过 {@code action} 传入;本方法只做外层保护:
     * 已经是 {@link ProtocolException} 的异常保持原样抛出,网络类异常按是否超时细分,
     * 其它 RestClient/Jackson 运行时异常兜底成 UNKNOWN。</p>
     *
     * @param method HTTP 方法名,只用于异常消息
     * @param uri    请求 URI,只用于异常消息
     * @param action 真正发起 HTTP 请求并读取响应的动作
     * @param <T>    响应类型
     * @return action 返回的响应对象
     */
    private <T> T execute(String method, String uri, Supplier<T> action) {
        try {
            // 正常路径:执行 RestClient exchange 回调,由下层负责读响应体。
            return action.get();
        } catch (ProtocolException ex) {
            // 下层已经完成协议错误映射时,这里不能再包一层 UNKNOWN,否则会丢失 errorCode/httpStatus。
            throw ex;
        } catch (ResourceAccessException ex) {
            // ResourceAccessException 表示请求尚未拿到可用 HTTP 响应,通常是连接失败、读超时、DNS 等网络问题。
            ProtocolErrorCode code = hasCause(ex, SocketTimeoutException.class)
                    ? ProtocolErrorCode.TIMEOUT
                    : ProtocolErrorCode.NETWORK;
            throw new ProtocolException(code, "协议层 " + method + " " + uri + " 网络异常", ex);
        } catch (RuntimeException ex) {
            // 其它运行时异常通常来自请求体序列化、RestClient 内部错误等,统一按未知协议调用失败处理。
            throw ProtocolException.unknown("协议层 " + method + " " + uri + " 调用失败", ex);
        }
    }

    /**
     * 读取并反序列化一个带响应体的 HTTP 响应。
     *
     * <p>本方法先调用 {@link #ensureSuccess(URI, ClientHttpResponse)} 处理非 2xx。
     * 只有确认状态码成功后,才读取 body 并按调用方指定类型做 JSON 反序列化。</p>
     *
     * @param uri          实际请求 URI,用于异常消息定位
     * @param response     RestClient 回调传入的原始响应
     * @param responseType 目标响应类型
     * @param <T>          响应类型
     * @return 反序列化后的响应对象
     * @throws IOException Spring response 状态读取可能抛出的 I/O 异常
     */
    private <T> T readTyped(URI uri, ClientHttpResponse response, Class<T> responseType) throws IOException {
        // 非 2xx 会在这里被转换成 ProtocolException;后续逻辑只处理成功响应。
        ensureSuccess(uri, response);
        // response body 只能稳定读取一次,所以先读成字符串,再交给 Jackson 解析。
        String body = readBody(uri, response);
        try {
            // 2xx 响应按 adapter 指定的 record/class 类型解析。
            return MAPPER.readValue(body, responseType);
        } catch (Exception ex) {
            // 响应体可能不是期望 JSON 或字段类型不匹配;异常消息只放截断预览,避免巨型 body 污染日志。
            throw ProtocolException.unknown(
                    "协议层响应反序列化失败 uri=" + uri
                            + " type=" + responseType.getSimpleName()
                            + " bodyPreview=" + preview(body),
                    ex);
        }
    }

    /**
     * 校验 HTTP 状态码,把非 2xx 响应转换为带协议元数据的 {@link ProtocolException}。
     *
     * <p>协议层错误体约定包含 {@code code/message/details};这里会尽量解析这些字段。
     * 即使错误体不是合法 JSON,也会保留 HTTP 状态码并给出 body 预览,避免调用方拿到裸异常。</p>
     *
     * @param uri      实际请求 URI,用于读取 body 失败时定位
     * @param response RestClient 回调传入的原始响应
     * @throws IOException Spring response 状态读取可能抛出的 I/O 异常
     */
    private void ensureSuccess(URI uri, ClientHttpResponse response) throws IOException {
        // 2xx 是唯一成功路径;生命周期 online 的 202 也属于这里。
        if (response.getStatusCode().is2xxSuccessful()) {
            return;
        }
        // 非 2xx 需要同时保留 HTTP 状态码和协议层 code,编排层后续要靠这些信息决定重试/刷新 owner。
        int httpStatus = response.getStatusCode().value();
        // 错误体里可能有 code/message/details;先读出来再做映射。
        String body = readBody(uri, response);
        // 解析失败不会抛出,会返回空错误体,这样至少还能把 HTTP 状态传出去。
        ErrorResponseBody error = parseErrorBody(body);
        // protocolCode 是协议层原始 code,例如 NOT_OWNER/ONLINE_LIMITED/PROXY_REQUIRED。
        String protocolCode = error.code();
        // errorCode 是 armada 防腐层内部枚举,编排层不要直接依赖协议 wire 字符串。
        ProtocolErrorCode errorCode = mapErrorCode(protocolCode, httpStatus);
        // 元数据保留给上层:NOT_OWNER 需要 ownerEndpoint,限流需要 retryAfterMs。
        ProtocolException.Metadata metadata = ProtocolException.Metadata.of(
                httpStatus,
                protocolCode,
                error.retryAfterMs(),
                error.ownerEndpoint());

        // 抛出统一异常:message 面向日志排障,结构化字段面向业务编排。
        throw new ProtocolException(
                errorCode,
                metadata,
                "协议层错误 " + httpStatus + " " + safeCode(protocolCode) + ": " + error.messageOrBody(body),
                null);
    }

    /**
     * 尝试解析协议层错误响应体。
     *
     * <p>错误体解析属于辅助信息提取,不能因为错误体格式异常而覆盖真正的 HTTP 错误。
     * 所以本方法解析失败时返回空对象,由 {@link #ensureSuccess(URI, ClientHttpResponse)}
     * 继续按 HTTP 状态码兜底。</p>
     *
     * @param body 原始响应体
     * @return 解析后的错误体;解析失败时返回空错误体
     */
    private static ErrorResponseBody parseErrorBody(String body) {
        // 无 body 时没有协议 code/message/details,交给 HTTP 状态码兜底。
        if (body == null || body.isBlank()) {
            return ErrorResponseBody.empty();
        }
        try {
            // 协议层标准错误体:{code,message,details};未知字段由 ObjectMapper 忽略。
            ErrorResponseBody parsed = MAPPER.readValue(body, ErrorResponseBody.class);
            return parsed == null ? ErrorResponseBody.empty() : parsed;
        } catch (Exception ignored) {
            // 错误体可能是纯文本或 HTML;不要在解析错误体时吞掉原本的 HTTP 错误语义。
            return ErrorResponseBody.empty();
        }
    }

    /**
     * 将协议层原始错误码映射为 armada 防腐层错误码。
     *
     * @param protocolCode 协议层返回的 {@code code}
     * @param httpStatus   HTTP 状态码
     * @return 防腐层错误码
     */
    private static ProtocolErrorCode mapErrorCode(String protocolCode, int httpStatus) {
        // 优先信任协议层 code,因为 NOT_OWNER/ONLINE_LIMITED 等语义无法只靠 HTTP 状态区分。
        if (protocolCode != null && !protocolCode.isBlank()) {
            try {
                // 两边同名时直接映射,避免维护一张重复映射表。
                return ProtocolErrorCode.valueOf(protocolCode);
            } catch (IllegalArgumentException ignored) {
                // 新协议码先兜底 UNKNOWN,后续按业务需要再加入 ProtocolErrorCode。
                return ProtocolErrorCode.UNKNOWN;
            }
        }
        // 没有协议 code 但状态码是错误,至少保留 HTTP_ERROR,避免落成完全未知。
        return httpStatus >= 400 ? ProtocolErrorCode.HTTP_ERROR : ProtocolErrorCode.UNKNOWN;
    }

    /**
     * 生成异常消息中展示的安全错误码文本。
     *
     * @param protocolCode 协议层原始错误码
     * @return 可放入异常消息的错误码;为空时使用 HTTP_ERROR
     */
    private static String safeCode(String protocolCode) {
        return protocolCode == null || protocolCode.isBlank() ? ProtocolErrorCode.HTTP_ERROR.name() : protocolCode;
    }

    /**
     * 读取响应体为 UTF-8 字符串。
     *
     * <p>RestClient 的 {@code ClientHttpResponse} body 是流,只能消费一次。
     * 本方法集中读取并在 try-with-resources 中关闭流,避免 adapter 重复处理 I/O 细节。</p>
     *
     * @param uri      实际请求 URI,用于异常消息定位
     * @param response 原始响应
     * @return 响应体字符串
     */
    private static String readBody(URI uri, ClientHttpResponse response) {
        // try-with-resources 负责关闭 body 输入流;外层 exchange(..., true) 负责关闭整个 response。
        try (InputStream input = response.getBody()) {
            // 协议层 HTTP JSON 统一按 UTF-8 读取。
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            // 读 body 失败时已经无法恢复,统一转为 ProtocolException,保留 uri 便于定位。
            throw ProtocolException.unknown("协议层响应体读取失败 uri=" + uri, ex);
        }
    }

    /**
     * 生成响应体预览文本。
     *
     * <p>异常消息需要一点 body 内容辅助排查,但不能把大响应体完整塞进日志。
     * 本方法把预览长度限制在 {@link #BODY_PREVIEW_MAX_CHARS}。</p>
     *
     * @param body 原始响应体
     * @return 可放入异常消息的短文本
     */
    private static String preview(String body) {
        // 明确区分真正 null 和空字符串,方便排查 HTTP 客户端行为。
        if (body == null) {
            return "null";
        }
        // 小响应体直接展示,通常是协议层错误 JSON。
        if (body.length() <= BODY_PREVIEW_MAX_CHARS) {
            return body;
        }
        // 大响应体只截断头部,并带上原始长度,方便判断是否异常过大。
        return body.substring(0, BODY_PREVIEW_MAX_CHARS) + "...(truncated len=" + body.length() + ")";
    }

    /**
     * 判断异常链上是否包含指定类型的 cause。
     *
     * <p>Spring 的 {@link ResourceAccessException} 往往会把真正的 socket 异常包在 cause 链内部;
     * 这里向下遍历,用于把 {@link SocketTimeoutException} 映射成 TIMEOUT。</p>
     *
     * @param throwable  异常链起点
     * @param targetType 目标异常类型
     * @return true 表示异常链中存在目标类型
     */
    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> targetType) {
        // 从当前异常一路向 cause 追溯,直到链尾。
        Throwable current = throwable;
        while (current != null) {
            // 使用 isInstance 支持目标类型的子类。
            if (targetType.isInstance(current)) {
                return true;
            }
            // 继续检查下一层 cause。
            current = current.getCause();
        }
        return false;
    }

    private record ErrorResponseBody(String code, String message, Map<String, Object> details) {

        /**
         * 构造空错误体。
         *
         * @return 不含协议 code/message/details 的错误体
         */
        private static ErrorResponseBody empty() {
            return new ErrorResponseBody(null, null, Map.of());
        }

        /**
         * 获取优先展示的错误消息。
         *
         * @param rawBody 原始响应体
         * @return 协议 message 非空时返回 message,否则返回 body 预览
         */
        private String messageOrBody(String rawBody) {
            // 标准错误体优先使用 message,它通常是协议层给人的可读原因。
            if (message != null && !message.isBlank()) {
                return message;
            }
            // 非标准错误体没有 message,用响应体预览保留排障线索。
            return rawBody == null ? "" : preview(rawBody);
        }

        /**
         * 从 details 中提取协议层建议的重试等待时间。
         *
         * @return retryAfterMs;不存在或类型不对时返回 null
         */
        private Long retryAfterMs() {
            // details 可能为空,也可能没有 retryAfterMs。
            Object value = details == null ? null : details.get("retryAfterMs");
            // Jackson 会把 JSON number 反序列化成 Number 子类,统一转成 long。
            if (value instanceof Number number) {
                return number.longValue();
            }
            return null;
        }

        /**
         * 从 details 中提取 owner worker endpoint。
         *
         * @return ownerEndpoint;不存在、空白或类型不对时返回 null
         */
        private String ownerEndpoint() {
            // NOT_OWNER 场景下协议层会在 details.ownerEndpoint 返回归属 worker 地址。
            Object value = details == null ? null : details.get("ownerEndpoint");
            return value instanceof String text && !text.isBlank() ? text : null;
        }
    }
}
