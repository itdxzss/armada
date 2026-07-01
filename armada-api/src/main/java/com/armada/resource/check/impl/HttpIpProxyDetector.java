package com.armada.resource.check.impl;

import com.armada.resource.check.IpProxyCheckProperties;
import com.armada.resource.check.IpProxyCheckRequest;
import com.armada.resource.check.IpProxyCheckResult;
import com.armada.resource.check.IpProxyCheckTiming;
import com.armada.resource.check.IpProxyDetector;
import com.armada.resource.model.ProxyProtocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 通过代理检测真实出口 IP 与 WhatsApp 官方站点连通性的适配器。
 *
 * <p>检测成功标准不是第三方 IP 查询成功,而是通过同一代理 session 获取出口 IP 后,
 * 对 {@code web.whatsapp.com:443} 建立代理隧道并收到 WhatsApp 侧明确 HTTP 响应。</p>
 */
@Component
public class HttpIpProxyDetector implements IpProxyDetector {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EGRESS_IP_HOST = "api.ipify.org";
    private static final String EGRESS_IP_PATH = "/";
    private static final String GEO_API_HOST = "ip-api.com";
    private static final int GEO_API_PORT = 80;
    private static final String GEO_API_PATH_PREFIX = "/json/";
    private static final String GEO_API_FIELDS = "?fields=status,message,query,countryCode,regionName,city,isp,lat,lon";
    private static final String WHATSAPP_HOST = "web.whatsapp.com";
    private static final int WHATSAPP_PORT = 443;
    private static final String WHATSAPP_PROBE_REQUEST = "GET / HTTP/1.1\r\n"
            + "Host: web.whatsapp.com\r\n"
            + "User-Agent: armada-ip-proxy-check/1.0\r\n"
            + "Connection: close\r\n\r\n";
    private static final int HTTP_MIN_STATUS = 100;
    private static final int HTTP_MAX_STATUS = 599;
    private static final int HTTP_SUCCESS_MIN = 200;
    private static final int HTTP_SUCCESS_MAX_EXCLUSIVE = 300;
    private static final int ERROR_MAX_LENGTH = 512;

    private final IpProxyCheckProperties properties;

    public HttpIpProxyDetector(IpProxyCheckProperties properties) {
        this.properties = properties;
    }

    /**
     * 执行真实代理检测。
     *
     * @param request 代理检测请求
     * @return 成功或失败结果
     */
    @Override
    public IpProxyCheckResult check(IpProxyCheckRequest request) {
        long started = System.nanoTime();
        long checkedAt = System.currentTimeMillis();
        long egressMs = 0;
        long geoMs = 0;
        long whatsappConnectMs = 0;
        long whatsappProbeMs = 0;
        try {
            validateRequest(request);

            String outboundIp;
            long egressStarted = System.nanoTime();
            try {
                outboundIp = resolveOutboundIp(request);
            } finally {
                egressMs = elapsedMs(egressStarted);
            }
            ensureWithinTotalTimeout(started);

            WhatsappProbeResult whatsapp = null;
            long whatsappStarted = System.nanoTime();
            try {
                whatsapp = probeWhatsapp(request);
            } finally {
                if (whatsapp == null) {
                    whatsappConnectMs = elapsedMs(whatsappStarted);
                }
            }
            whatsappConnectMs = whatsapp.connectMs();
            whatsappProbeMs = whatsapp.probeMs();
            if (!isWhatsappStatusAcceptable(whatsapp.httpStatus())) {
                throw new IOException("WhatsApp 未返回明确响应");
            }
            ensureWithinTotalTimeout(started);

            IpProxyCheckResult geo = null;
            long geoStarted = System.nanoTime();
            try {
                geo = lookupGeo(outboundIp, request.id(), checkedAt);
                if (!geo.success()) {
                    geo = null;
                }
            } catch (IOException e) {
                geo = null;
            } finally {
                geoMs = elapsedMs(geoStarted);
            }

            return IpProxyCheckResult.success(
                    request.id(),
                    outboundIp,
                    geo == null ? null : geo.countryCode(),
                    geo == null ? null : geo.location(),
                    geo == null ? null : geo.isp(),
                    geo == null ? null : geo.latitude(),
                    geo == null ? null : geo.longitude(),
                    whatsapp.httpStatus(),
                    new IpProxyCheckTiming(elapsedMs(started), egressMs, geoMs, whatsappConnectMs, whatsappProbeMs),
                    checkedAt);
        } catch (Exception e) {
            String error = safeError(request, e);
            return IpProxyCheckResult.failed(
                    request == null ? null : request.id(),
                    error,
                    error,
                    null,
                    new IpProxyCheckTiming(elapsedMs(started), egressMs, geoMs, whatsappConnectMs, whatsappProbeMs),
                    checkedAt);
        }
    }

    /**
     * 解析 ip-api JSON 响应。
     *
     * @param id        ip_proxy 主键
     * @param json      ip-api 响应体
     * @param checkedAt 检测时间
     * @return 检测结果
     */
    public static IpProxyCheckResult parseIpApiJson(Long id, String json, Long checkedAt) {
        try {
            JsonNode root = MAPPER.readTree(json);
            String status = text(root, "status");
            if (!"success".equalsIgnoreCase(status)) {
                String message = text(root, "message");
                return IpProxyCheckResult.failed(id,
                        StringUtils.hasText(message) ? "IP查询失败: " + message : "IP查询失败", checkedAt);
            }
            return IpProxyCheckResult.geoSuccess(
                    id,
                    text(root, "query"),
                    upper(text(root, "countryCode")),
                    location(text(root, "city"), text(root, "regionName")),
                    text(root, "isp"),
                    decimal(root, "lat"),
                    decimal(root, "lon"),
                    checkedAt);
        } catch (Exception e) {
            return IpProxyCheckResult.failed(id, "IP查询响应解析失败: " + e.getMessage(), checkedAt);
        }
    }

    static String parsePlainIp(String body) throws IOException {
        String value = body == null ? "" : body.trim();
        if (!StringUtils.hasText(value)) {
            throw new IOException("出口 IP 响应为空");
        }
        if (!value.matches("[0-9a-fA-F:.]{3,64}")) {
            throw new IOException("出口 IP 响应非法: " + value);
        }
        return value;
    }

    static boolean isWhatsappStatusAcceptable(Integer statusCode) {
        return statusCode != null && statusCode >= HTTP_MIN_STATUS && statusCode <= HTTP_MAX_STATUS;
    }

    private String resolveOutboundIp(IpProxyCheckRequest request) throws IOException {
        String body = checkHttpProxyPlain(request, "http://" + EGRESS_IP_HOST + EGRESS_IP_PATH, EGRESS_IP_HOST);
        return parsePlainIp(body);
    }

    private String checkHttpProxyPlain(IpProxyCheckRequest request, String absoluteUrl, String hostHeader)
            throws IOException {
        try (Socket socket = openSocket(request.host(), request.port())) {
            OutputStream out = socket.getOutputStream();
            StringBuilder headers = new StringBuilder()
                    .append("GET ").append(absoluteUrl).append(" HTTP/1.0\r\n")
                    .append("Host: ").append(hostHeader).append("\r\n")
                    .append("Connection: close\r\n");
            appendProxyAuthorization(headers, request);
            headers.append("\r\n");
            out.write(headers.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return readHttpBody(socket.getInputStream());
        }
    }

    private IpProxyCheckResult lookupGeo(String outboundIp, Long id, long checkedAt) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(GEO_API_HOST, GEO_API_PORT), properties.getTimeout().getConnectMs());
            socket.setSoTimeout(properties.getTimeout().getReadMs());
            OutputStream out = socket.getOutputStream();
            String path = GEO_API_PATH_PREFIX + outboundIp + GEO_API_FIELDS;
            String request = "GET " + path + " HTTP/1.0\r\n"
                    + "Host: " + GEO_API_HOST + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return parseIpApiJson(id, readHttpBody(socket.getInputStream()), checkedAt);
        }
    }

    private WhatsappProbeResult probeWhatsapp(IpProxyCheckRequest request) throws IOException {
        return probeWhatsappViaHttpProxy(request);
    }

    private WhatsappProbeResult probeWhatsappViaHttpProxy(IpProxyCheckRequest request) throws IOException {
        long connectStarted = System.nanoTime();
        try (Socket socket = openSocket(request.host(), request.port())) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            StringBuilder headers = new StringBuilder()
                    .append("CONNECT ").append(WHATSAPP_HOST).append(":").append(WHATSAPP_PORT).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(WHATSAPP_HOST).append(":").append(WHATSAPP_PORT).append("\r\n");
            appendProxyAuthorization(headers, request);
            headers.append("\r\n");
            out.write(headers.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            int connectStatus = readHttpStatus(in);
            if (connectStatus < HTTP_SUCCESS_MIN || connectStatus >= HTTP_SUCCESS_MAX_EXCLUSIVE) {
                throw new IOException("WhatsApp CONNECT 失败: HTTP " + connectStatus);
            }
            long connectMs = elapsedMs(connectStarted);
            long probeStarted = System.nanoTime();
            int whatsappStatus = probeTlsHttp(socket);
            return new WhatsappProbeResult(whatsappStatus, connectMs, elapsedMs(probeStarted));
        }
    }

    private int probeTlsHttp(Socket socket) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket ssl = (SSLSocket) factory.createSocket(socket, WHATSAPP_HOST, WHATSAPP_PORT, false)) {
            ssl.setUseClientMode(true);
            ssl.setSoTimeout(properties.getTimeout().getReadMs());
            ssl.startHandshake();
            OutputStream out = ssl.getOutputStream();
            out.write(WHATSAPP_PROBE_REQUEST.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return readHttpStatus(ssl.getInputStream());
        }
    }

    private Socket openSocket(String host, Integer port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), properties.getTimeout().getConnectMs());
        socket.setSoTimeout(properties.getTimeout().getReadMs());
        return socket;
    }

    private static void appendProxyAuthorization(StringBuilder headers, IpProxyCheckRequest request) {
        if (StringUtils.hasText(request.username()) || StringUtils.hasText(request.password())) {
            String credential = value(request.username()) + ":" + value(request.password());
            headers.append("Proxy-Authorization: Basic ")
                    .append(Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8)))
                    .append("\r\n");
        }
    }

    private static int readHttpStatus(InputStream in) throws IOException {
        return parseHttpStatus(readHttpHeader(in));
    }

    private static String readHttpBody(InputStream in) throws IOException {
        String header = readHttpHeader(in);
        int statusCode = parseHttpStatus(header);
        if (statusCode != 200) {
            throw new IOException("HTTP请求失败: " + firstStatusLine(header));
        }
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static int parseHttpStatus(String header) throws IOException {
        String[] parts = firstStatusLine(header).split(" ");
        if (parts.length < 2) {
            throw new IOException("代理返回非HTTP响应");
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("HTTP状态码非法: " + firstStatusLine(header), e);
        }
    }

    private static String firstStatusLine(String header) {
        int firstLineEnd = header.indexOf("\r\n");
        return firstLineEnd < 0 ? header : header.substring(0, firstLineEnd);
    }

    private static String readHttpHeader(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int previous3 = -1;
        int previous2 = -1;
        int previous1 = -1;
        int current;
        while ((current = in.read()) != -1) {
            buffer.write(current);
            if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && current == '\n') {
                return buffer.toString(StandardCharsets.ISO_8859_1);
            }
            previous3 = previous2;
            previous2 = previous1;
            previous1 = current;
        }
        throw new IOException("HTTP响应头提前结束");
    }

    private static void validateRequest(IpProxyCheckRequest request) {
        if (request == null || request.id() == null) {
            throw new IllegalArgumentException("代理 ID 不能为空");
        }
        if (!StringUtils.hasText(request.host()) || request.port() == null || request.port() <= 0) {
            throw new IllegalArgumentException("代理地址或端口非法");
        }
        ProxyProtocol protocol = ProxyProtocol.fromCode(request.protocol());
        if (protocol != ProxyProtocol.HTTP) {
            throw new IllegalArgumentException("当前仅支持HTTP代理检测");
        }
    }

    private void ensureWithinTotalTimeout(long started) throws IOException {
        int totalMs = properties.getTimeout().getTotalMs();
        if (totalMs > 0 && elapsedMs(started) > totalMs) {
            throw new IOException("代理检测超时");
        }
    }

    private static long elapsedMs(long startedNano) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNano);
    }

    private static String safeError(IpProxyCheckRequest request, Exception e) {
        String message = e.getMessage();
        if (!StringUtils.hasText(message)) {
            message = e.getClass().getSimpleName();
        }
        if (request != null) {
            message = removeSecret(message, request.username());
            message = removeSecret(message, request.password());
        }
        return message.length() > ERROR_MAX_LENGTH ? message.substring(0, ERROR_MAX_LENGTH) : message;
    }

    private static String removeSecret(String message, String secret) {
        return StringUtils.hasText(secret) ? message.replace(secret, "***") : message;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(java.util.Locale.ROOT);
    }

    private static String location(String city, String regionName) {
        if (StringUtils.hasText(city) && StringUtils.hasText(regionName)) {
            return city + ", " + regionName;
        }
        return StringUtils.hasText(city) ? city : regionName;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.decimalValue() : null;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private record WhatsappProbeResult(Integer httpStatus, long connectMs, long probeMs) {
    }
}
