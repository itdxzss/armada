package com.armada.resource.model.vo;

import java.math.BigDecimal;

/**
 * IP 代理列表出参（返回前端的视图对象）。
 *
 * <p>字段 camelCase，出参 JSON 即 camelCase（全局 Jackson 默认命名,无 snake 转换）。
 * {@code protocol/status/ownership} 出码 + 配套 {@code *Label} 展示名（后端用枚举算好，前端不维护映射）。
 * {@code createdAt} 为 epoch 毫秒，前端按 Asia/Shanghai 展示。</p>
 */
public record IpProxyVO(

        /** 代理 ID。 */
        Long id,

        /** 代理地址 host:port。 */
        String proxyAddress,

        /** 协议码:1=HTTP 2=SOCKETS。 */
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

        /** 鉴权密码。 */
        String password,

        /** 有效账号数；当前 IP 代理为 1:1 绑定，已绑定账号时为 1，否则为 0。 */
        Integer validAccountCount,

        /** 来源。 */
        String source,

        /** 备注。 */
        String remark,

        /** 分配方式:smart=智能分配 mixed=混合分组。 */
        String allocationMode,

        /** 分配方式展示中文。 */
        String allocationModeLabel,

        /** 最近抽检时间(epoch毫秒);NULL=尚未抽检。 */
        Long lastSampleCheckAt,

        /** 检测出的 ISO2 国家码。 */
        String detectedCountryCode,

        /** 真实出口公网 IP。 */
        String outboundIp,

        /** 检测出的地理位置。 */
        String detectedLocation,

        /** 检测出的 ISP。 */
        String detectedIsp,

        /** 检测纬度。 */
        BigDecimal detectedLatitude,

        /** 检测经度。 */
        BigDecimal detectedLongitude,

        /** 检测失败次数。 */
        Integer checkFailCount,

        /** 最近一次检测失败原因。 */
        String lastCheckError,

        /** 创建时间，epoch 毫秒（UTC 时刻）；前端按 Asia/Shanghai 格式化。 */
        Long createdAt) {
}
