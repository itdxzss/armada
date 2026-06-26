package com.armada.platform.proxy;

import com.armada.platform.protocol.port.account.command.ProxyDescriptor;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 运行时代理解析器。
 *
 * <p>本组件只负责把代理端点字段解析成协议层 online 需要的 {@link ProxyDescriptor}:
 * 协议码转 wire 字符串、拼完整代理 URL、从 password 中提取 sticky sessionId。
 * 代理行分配、绑定、回收和数据库状态流转不在这里做。</p>
 */
@Component
public class ProxyResolver {

    private static final Pattern SESSION_PATTERN = Pattern.compile("_session-([A-Za-z0-9]+)");
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65_535;
    private static final String WIRE_HTTP = "http";
    private static final String WIRE_SOCKS5 = "socks5";

    /**
     * 将代理端点解析成协议层代理描述。
     *
     * @param endpoint 代理端点字段
     * @return 协议层 online 请求体中的 proxy 描述
     * @throws BusinessException 当代理协议、地址、端口、鉴权或 sticky session 缺失/非法时抛出
     */
    public ProxyDescriptor resolve(ProxyEndpoint endpoint) {
        if (endpoint == null) {
            throw validation("代理端点不能为空");
        }
        String protocol = toWireProtocol(endpoint.protocolCode());
        String host = requireText(endpoint.host(), "代理网关不能为空");
        Integer port = requirePort(endpoint.port());
        ProxyCredentials credentials = requireCredentials(endpoint.credentials());
        String username = requireText(credentials.username(), "代理用户名不能为空");
        String password = requireText(credentials.password(), "代理密码不能为空");
        String sessionId = parseSession(password);
        String country = requireText(endpoint.country(), "代理国家不能为空");
        String url = protocol + "://" + username + ":" + password + "@" + host + ":" + port;
        return new ProxyDescriptor(protocol, url, sessionId, country);
    }

    private static String toWireProtocol(int protocolCode) {
        return switch (protocolCode) {
            case ProxyEndpoint.PROTOCOL_HTTP -> WIRE_HTTP;
            case ProxyEndpoint.PROTOCOL_SOCKS5 -> WIRE_SOCKS5;
            default -> throw validation("非法的代理协议: " + protocolCode);
        };
    }

    private static String parseSession(String password) {
        Matcher matcher = SESSION_PATTERN.matcher(password);
        if (!matcher.find()) {
            throw validation("代理密码缺少 sticky session");
        }
        return matcher.group(1);
    }

    private static ProxyCredentials requireCredentials(ProxyCredentials credentials) {
        if (credentials == null) {
            throw validation("代理鉴权信息不能为空");
        }
        return credentials;
    }

    private static Integer requirePort(Integer port) {
        if (port == null || port < MIN_PORT || port > MAX_PORT) {
            throw validation("代理端口非法: " + port);
        }
        return port;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw validation(message);
        }
        return value.trim();
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }
}
