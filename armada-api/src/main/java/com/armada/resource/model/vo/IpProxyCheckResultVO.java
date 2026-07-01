package com.armada.resource.model.vo;

import java.math.BigDecimal;

/**
 * IP 代理检测结果出参。
 *
 * @param id                 ip_proxy 主键
 * @param checkStatus        本次检测状态:success/failed
 * @param connectionStatus   代理池行状态展示
 * @param whatsappStatus     WhatsApp 可用性状态;当前检测不登录 WhatsApp,固定 unknown
 * @param outboundIp         真实出口 IP
 * @param countryCode        检测出的 ISO2 国家码
 * @param region             最终写入/保留的 region 中文名
 * @param location           检测出的地理位置
 * @param isp                检测出的 ISP
 * @param detectedLatitude   检测纬度
 * @param detectedLongitude  检测经度
 * @param checkedAt          检测时间(epoch毫秒)
 * @param errorMessage       失败原因
 */
public record IpProxyCheckResultVO(
        Long id,
        String checkStatus,
        String connectionStatus,
        String whatsappStatus,
        String outboundIp,
        String countryCode,
        String region,
        String location,
        String isp,
        BigDecimal detectedLatitude,
        BigDecimal detectedLongitude,
        Long checkedAt,
        String errorMessage) {
}
