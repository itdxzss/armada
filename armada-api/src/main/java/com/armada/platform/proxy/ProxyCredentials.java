package com.armada.platform.proxy;

/**
 * 代理鉴权信息。
 *
 * <p>当前 IP 池导入格式为 {@code host:port:username:password}。password 可能内嵌供应商 sticky session
 * 参数,日志中禁止输出完整值。</p>
 *
 * @param username 代理鉴权用户名
 * @param password 代理鉴权密码;可能包含 {@code _session-xxx}
 */
public record ProxyCredentials(String username, String password) {
}
