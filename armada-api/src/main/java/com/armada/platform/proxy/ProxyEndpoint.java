package com.armada.platform.proxy;

/**
 * 运行时代理端点输入。
 *
 * <p>本 record 是 platform/proxy 的纯技术模型,用于承接上游从 ip_proxy 或其它代理来源取出的字段。
 * 它不依赖 resource 域实体,避免基础设施包反向依赖业务域。</p>
 *
 * @param protocolCode 代理协议码;当前沿用 ip_proxy.protocol:1=HTTP,2=SOCKS5
 * @param host         代理网关地址
 * @param port         代理端口
 * @param credentials  代理鉴权信息
 * @param country      出口国家/地区
 */
public record ProxyEndpoint(
        int protocolCode,
        String host,
        Integer port,
        ProxyCredentials credentials,
        String country
) {

    /**
     * HTTP 代理协议码。
     */
    public static final int PROTOCOL_HTTP = 1;

    /**
     * SOCKS5 代理协议码。
     */
    public static final int PROTOCOL_SOCKS5 = 2;
}
