package com.armada.resource.check;

/**
 * IP 代理真实出口检测端口。
 */
public interface IpProxyDetector {

    /**
     * 通过代理访问外部 IP 查询服务,返回真实出口信息或失败原因。
     *
     * @param request 代理检测请求
     * @return 检测结果;实现不得假成功
     */
    IpProxyCheckResult check(IpProxyCheckRequest request);
}
