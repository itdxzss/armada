package com.armada.account.model.dto;

/**
 * 单账号手动上线请求。
 *
 * <p>本刀由运营显式选择代理,后端不做自动分配和绑定状态流转。</p>
 *
 * @param proxyId 上线使用的 IP 代理主键
 */
public record AccountOnlineDTO(Long proxyId) {
}
