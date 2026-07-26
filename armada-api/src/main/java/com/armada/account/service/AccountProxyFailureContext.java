package com.armada.account.service;

/** 最近一次代理失败诊断中用于换 IP 和 attempt 串联的最小上下文。 */
public record AccountProxyFailureContext(String onlineAttemptId, Long proxyId) {
}
