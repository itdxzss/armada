package com.armada.resource.model.vo;

/**
 * IP 数据统计国家/地区明细行。
 *
 * <p>不返回代理密码,避免统计页扩大敏感信息暴露面。</p>
 */
public record IpProxyStatsDetailVO(

        /** 代理 ID。 */
        Long id,

        /** 代理网关地址。 */
        String proxyHost,

        /** 代理端口。 */
        Integer proxyPort,

        /** 代理地址 host:port。 */
        String proxyAddress,

        /** 协议码。 */
        Integer protocol,

        /** 协议展示名。 */
        String protocolLabel,

        /** 国家/地区中文快照。 */
        String region,

        /** 状态码。 */
        Integer status,

        /** 状态展示名。 */
        String statusLabel,

        /** 当前绑定账号 ID。 */
        Long boundAccountId,

        /** 来源。 */
        String source,

        /** 分配方式:smart=智能分配 mixed=混合分组。 */
        String allocationMode,

        /** 分配方式展示名。 */
        String allocationModeLabel,

        /** 归属码。 */
        Integer ownership,

        /** 归属展示名。 */
        String ownershipLabel,

        /** 最近抽检时间(epoch 毫秒)。 */
        Long lastSampleCheckAt,

        /** 入库时间(epoch 毫秒)。 */
        Long createdAt,

        /** 绑定时间(epoch 毫秒)。 */
        Long boundAt) {
}
