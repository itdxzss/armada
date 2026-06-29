package com.armada.resource.service;

import java.util.List;

/**
 * IP 代理删除编排服务。
 *
 * <p>删除 IP 前需要让账号域处理仍在线的绑定账号:离线账号不动,在线账号先换 IP 并重新投递上线命令。
 * 本接口把跨域编排从 {@link IpProxyService} 中拆开,避免 resource 代理池能力和 account 上线能力互相循环依赖。</p>
 */
public interface IpProxyDeletionService {

    /**
     * 批量删除 IP 代理。
     *
     * <p>空列表直接返回。非空时先触发在线绑定账号重登;重登编排失败则中断删除,避免在线账号失去可用代理。</p>
     *
     * @param ids 要删除的代理 ID 列表
     */
    void batchDelete(List<Long> ids);
}
