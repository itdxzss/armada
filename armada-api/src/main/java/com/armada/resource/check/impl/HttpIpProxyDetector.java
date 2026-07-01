package com.armada.resource.check.impl;

import com.armada.resource.model.ProxyProtocol;
import com.armada.resource.check.IpProxyCheckRequest;
import com.armada.resource.check.IpProxyCheckResult;
import com.armada.resource.check.IpProxyDetector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 通过代理访问外部 IP 查询服务的真实检测适配器。
 *
 * <p>实现不假成功:HTTP 代理走 absolute-form GET,SOCKS5 代理用 Socket 手写握手和账号密码认证,
 * 最终都通过代理访问 {@code ip-api.com} 获取出口 IP、国家码和归属信息。</p>
 */
@Component
public class HttpIpProxyDetector implements IpProxyDetector {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String IP_API_HOST = "ip-api.com";
    private static final int IP_API_PORT = 80;
    private static final String IP_API_PATH = "/json?fields=status,message,query,countryCode,regionName,city,isp,lat,lon";
    private static final String WHITESPACE = " ";
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int MAX_CREDENTIAL_BYTES = 255;
    private static final int ERROR_MAX_LENGTH = 512;

    /**
     * 执行真实代理检测。
     *
     * @param request 代理检测请求
     * @return 成功或失败结果
     */
    @Override
    public IpProxyCheckResult check(IpProxyCheckRequest request) {
        long checkedAt = System.currentTimeMillis();
        try {
            validateRequest(request);
            String body = ProxyProtocol.HTTP.code() == request.protocol()
                    ? checkHttpProxy(request)
                    : checkSocks5Proxy(request);
            return parseIpApiJson(request.id(), body, checkedAt);
        } catch (Exception e) {
            return IpProxyCheckResult.failed(request == null ? null : request.id(), safeError(request, e), checkedAt);
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
            return IpProxyCheckResult.success(
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

    private static String checkHttpProxy(IpProxyCheckRequest request) throws IOException {
        try (Socket socket = openSocket(request.host(), request.port())) {
            OutputStream out = socket.getOutputStream();
            StringBuilder headers = new StringBuilder()
                    .append("GET http://").append(IP_API_HOST).append(IP_API_PATH).append(" HTTP/1.0\r\n")
                    .append("Host: ").append(IP_API_HOST).append("\r\n")
                    .append("Connection: close\r\n");
            if (StringUtils.hasText(request.username()) || StringUtils.hasText(request.password())) {
                String credential = value(request.username()) + ":" + value(request.password());
                headers.append("Proxy-Authorization: Basic ")
                        .append(Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8)))
                        .append("\r\n");
            }
            headers.append("\r\n");
            out.write(headers.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return readHttpBody(socket.getInputStream());
        }
    }

    private static String checkSocks5Proxy(IpProxyCheckRequest request) throws IOException {
        try (Socket socket = openSocket(request.host(), request.port())) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            boolean hasCredential = StringUtils.hasText(request.username()) || StringUtils.hasText(request.password());
            if (hasCredential) {
                out.write(new byte[]{0x05, 0x02, 0x02, 0x00});
            } else {
                out.write(new byte[]{0x05, 0x01, 0x00});
            }
            out.flush();
            byte[] method = readExactly(in, 2);
            if (method[0] != 0x05 || method[1] == (byte) 0xFF) {
                throw new IOException("SOCKS5代理不支持可用认证方式");
            }
            if (method[1] == 0x02) {
                authenticateSocks5(out, in, request);
            }
            connectSocks5Target(out, in);
            String get = "GET " + IP_API_PATH + " HTTP/1.0\r\n"
                    + "Host: " + IP_API_HOST + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(get.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return readHttpBody(in);
        }
    }

    private static Socket openSocket(String host, Integer port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        return socket;
    }

    private static void authenticateSocks5(OutputStream out, InputStream in, IpProxyCheckRequest request)
            throws IOException {
        byte[] username = value(request.username()).getBytes(StandardCharsets.UTF_8);
        byte[] password = value(request.password()).getBytes(StandardCharsets.UTF_8);
        if (username.length > MAX_CREDENTIAL_BYTES || password.length > MAX_CREDENTIAL_BYTES) {
            throw new IOException("SOCKS5用户名或密码过长");
        }
        out.write(0x01);
        out.write(username.length);
        out.write(username);
        out.write(password.length);
        out.write(password);
        out.flush();
        byte[] response = readExactly(in, 2);
        if (response[1] != 0x00) {
            throw new IOException("SOCKS5认证失败");
        }
    }

    private static void connectSocks5Target(OutputStream out, InputStream in) throws IOException {
        byte[] host = IP_API_HOST.getBytes(StandardCharsets.ISO_8859_1);
        out.write(new byte[]{0x05, 0x01, 0x00, 0x03, (byte) host.length});
        out.write(host);
        out.write(new byte[]{0x00, (byte) IP_API_PORT});
        out.flush();

        byte[] head = readExactly(in, 4);
        if (head[1] != 0x00) {
            throw new IOException("SOCKS5连接目标失败,响应码=" + (head[1] & 0xFF));
        }
        int atyp = head[3] & 0xFF;
        if (atyp == 0x01) {
            readExactly(in, 4);
        } else if (atyp == 0x03) {
            int len = readExactly(in, 1)[0] & 0xFF;
            readExactly(in, len);
        } else if (atyp == 0x04) {
            readExactly(in, 16);
        } else {
            throw new IOException("SOCKS5响应地址类型非法");
        }
        readExactly(in, 2);
    }

    private static String readHttpBody(InputStream in) throws IOException {
        byte[] raw = in.readAllBytes();
        String response = new String(raw, StandardCharsets.ISO_8859_1);
        int headerEnd = response.indexOf("\r\n\r\n");
        if (headerEnd < 0) {
            throw new IOException("代理返回非HTTP响应");
        }
        String statusLine = response.substring(0, response.indexOf("\r\n"));
        if (!statusLine.contains(WHITESPACE + "200" + WHITESPACE)) {
            throw new IOException("IP查询HTTP失败: " + statusLine);
        }
        return new String(raw, headerEnd + 4, raw.length - headerEnd - 4, StandardCharsets.UTF_8);
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("代理连接提前关闭");
        }
        return bytes;
    }

    private static void validateRequest(IpProxyCheckRequest request) {
        if (request == null || request.id() == null) {
            throw new IllegalArgumentException("代理 ID 不能为空");
        }
        if (!StringUtils.hasText(request.host()) || request.port() == null || request.port() <= 0) {
            throw new IllegalArgumentException("代理地址或端口非法");
        }
        ProxyProtocol.fromCode(request.protocol());
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

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.decimalValue() : null;
    }

    private static String location(String city, String regionName) {
        if (StringUtils.hasText(city) && StringUtils.hasText(regionName)) {
            return city + ", " + regionName;
        }
        if (StringUtils.hasText(city)) {
            return city;
        }
        return StringUtils.hasText(regionName) ? regionName : null;
    }

    private static String upper(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(java.util.Locale.ROOT) : null;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
