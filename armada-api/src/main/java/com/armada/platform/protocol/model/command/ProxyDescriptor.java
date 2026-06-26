package com.armada.platform.protocol.model.command;

/**
 * 上线用代理出口描述(防腐层语义)。
 *
 * <p>由 ProxyResolver(④口)从 ip_proxy 行 + sticky session 拼出,作为 {@link OnlineCommand} 的一部分
 * 喂给协议层 online。各字段直接对应协议层 online 请求体里的 proxy 结构,adapter 透传不再翻译。</p>
 *
 * @param protocol  代理协议,取值 {@code "socks5"} / {@code "http"}(由 ProxyResolver 从 resource 侧协议码映射而来)
 * @param url       代理连接串(含主机/端口/凭据);日志须脱敏,严禁打全文
 * @param sessionId sticky 会话标识,复用同一出口 IP;由 ProxyResolver 复用已有值或新生成,online 前必须非空
 * @param country   出口国家/地区(来自 ip_proxy.region),供协议层归属/展示
 */
public record ProxyDescriptor(
        String protocol,
        String url,
        String sessionId,
        String country
) {
}
