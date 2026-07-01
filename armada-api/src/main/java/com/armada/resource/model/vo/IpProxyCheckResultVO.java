package com.armada.resource.model.vo;

import java.math.BigDecimal;

/**
 * IP 代理检测结果出参。
 *
 * @param id                 ip_proxy 主键
 * @param checkStatus        本次检测状态:success/failed
 * @param connectionStatus   代理池行状态展示
 * @param whatsappStatus     WhatsApp 官方响应状态,如 HTTP 400、detecting、failed 或 unknown
 * @param outboundIp         真实出口 IP
 * @param countryCode        检测出的 ISO2 国家码
 * @param detectedRegion     检测国家码对应的中文国家名,仅用于检测弹窗展示
 * @param region             最终写入/保留的业务 region 中文名
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
        String detectedRegion,
        String region,
        String location,
        String isp,
        BigDecimal detectedLatitude,
        BigDecimal detectedLongitude,
        Long checkedAt,
        String errorMessage) {
}
