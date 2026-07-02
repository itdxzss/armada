package com.armada.resource.service;

/**
 * 不可用 IP 重检结果摘要。
 *
 * @param scanned 本轮从库中拉取的不可用 IP 数
 * @param checked 已执行检测流程的 IP 数
 * @param failed  检测流程自身异常的 IP 数;代理检测失败不计入该值,会落库为不可用
 */
public record IpProxyRecheckResult(int scanned, int checked, int failed) {
}
