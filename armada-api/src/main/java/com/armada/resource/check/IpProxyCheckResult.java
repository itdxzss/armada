package com.armada.resource.check;

import java.math.BigDecimal;

/**
 * 单次 IP 代理出口检测结果。
 *
 * <p>该模型是检测端口返回给 resource service 的内部结果,不直接作为 HTTP 出参。</p>
 *
 * @param id           ip_proxy 主键
 * @param success      是否检测成功
 * @param outboundIp   代理真实出口 IP
 * @param countryCode  检测出的 ISO2 国家码
 * @param location     检测出的地理位置文本
 * @param isp          检测出的 ISP
 * @param latitude     检测纬度
 * @param longitude    检测经度
 * @param checkedAt    检测时间(epoch毫秒)
 * @param errorMessage 失败原因;成功时为空
 */
public record IpProxyCheckResult(
        Long id,
        boolean success,
        String outboundIp,
        String countryCode,
        String location,
        String isp,
        BigDecimal latitude,
        BigDecimal longitude,
        Long checkedAt,
        String errorMessage) {

    /** 构造成功结果。 */
    public static IpProxyCheckResult success(Long id,
                                             String outboundIp,
                                             String countryCode,
                                             String location,
                                             String isp,
                                             BigDecimal latitude,
                                             BigDecimal longitude,
                                             Long checkedAt) {
        return new IpProxyCheckResult(
                id, true, outboundIp, countryCode, location, isp, latitude, longitude, checkedAt, null);
    }

    /** 构造失败结果。 */
    public static IpProxyCheckResult failed(Long id, String errorMessage, Long checkedAt) {
        return new IpProxyCheckResult(id, false, null, null, null, null, null, null, checkedAt, errorMessage);
    }
}
