package com.armada.resource.model.vo;

/**
 * IP 代理列表出参（返回前端的视图对象）。
 *
 * <p>字段 camelCase，出参 JSON 即 camelCase（全局 Jackson 默认命名,无 snake 转换）。
 * {@code protocol/status/ownership} 出码 + 配套 {@code *Label} 中文（后端用枚举算好，前端不维护映射）。
 * {@code createdAt} 为 epoch 毫秒，前端按 Asia/Shanghai 展示。{@code password} 已脱敏。</p>
 */
public record IpProxyVO(

        /** 代理 ID。 */
        Long id,

        /** 代理地址 host:port。 */
        String proxyAddress,

        /** 协议码:1=HTTP 2=SOCKS5。 */
        Integer protocol,

        /** 协议展示名。 */
        String protocolLabel,

        /** 国家/分组中文展示名。 */
        String region,

        /** 状态码:1=空闲 2=使用中 3=不可用。 */
        Integer status,

        /** 状态展示中文。 */
        String statusLabel,

        /** 归属码:1=租户自有 2=平台池 3=租借。 */
        Integer ownership,

        /** 归属展示中文。 */
        String ownershipLabel,

        /** 鉴权用户名。 */
        String username,

        /** 鉴权密码（脱敏）。 */
        String password,

        /** 来源。 */
        String source,

        /** 备注。 */
        String remark,

        /** 创建时间，epoch 毫秒（UTC 时刻）；前端按 Asia/Shanghai 格式化。 */
        Long createdAt) {
}
