package com.armada.resource.check;

/**
 * 单次 IP 代理出口检测请求。
 *
 * @param id       ip_proxy 主键
 * @param protocol 代理协议码:1=HTTP 2=SOCKS5
 * @param host     代理网关地址
 * @param port     代理端口
 * @param username 鉴权用户名
 * @param password 鉴权密码
 */
public record IpProxyCheckRequest(
        Long id,
        Integer protocol,
        String host,
        Integer port,
        String username,
        String password) {
}
