package com.armada.resource.check;

import java.math.BigDecimal;

/**
 * 单次 IP 代理出口与 WhatsApp 连通性检测结果。
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
 * @param whatsappReachable     WhatsApp 官方站点是否可通过代理隧道连通
 * @param whatsappHttpStatus    WhatsApp 探测返回的 HTTP 状态码
 * @param whatsappErrorMessage  WhatsApp 连通性失败原因
 * @param timing                各阶段耗时
 * @param checkedAt             检测时间(epoch毫秒)
 * @param errorMessage          失败原因;成功时为空
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
        boolean whatsappReachable,
        Integer whatsappHttpStatus,
        String whatsappErrorMessage,
        IpProxyCheckTiming timing,
        Long checkedAt,
        String errorMessage) {

    /** 构造出口 IP 与 WhatsApp 连通性都通过的成功结果。 */
    public static IpProxyCheckResult success(Long id,
                                             String outboundIp,
                                             String countryCode,
                                             String location,
                                             String isp,
                                             BigDecimal latitude,
                                             BigDecimal longitude,
                                             Integer whatsappHttpStatus,
                                             IpProxyCheckTiming timing,
                                             Long checkedAt) {
        return new IpProxyCheckResult(
                id, true, outboundIp, countryCode, location, isp, latitude, longitude,
                true, whatsappHttpStatus, null, timing == null ? IpProxyCheckTiming.zero() : timing, checkedAt, null);
    }

    /** 构造只用于地理信息查询成功的中间结果,不代表 WhatsApp 已连通。 */
    public static IpProxyCheckResult geoSuccess(Long id,
                                                String outboundIp,
                                                String countryCode,
                                                String location,
                                                String isp,
                                                BigDecimal latitude,
                                                BigDecimal longitude,
                                                Long checkedAt) {
        return new IpProxyCheckResult(
                id, true, outboundIp, countryCode, location, isp, latitude, longitude,
                false, null, null, IpProxyCheckTiming.zero(), checkedAt, null);
    }

    /** 构造失败结果。 */
    public static IpProxyCheckResult failed(Long id,
                                            String errorMessage,
                                            String whatsappErrorMessage,
                                            Integer whatsappHttpStatus,
                                            IpProxyCheckTiming timing,
                                            Long checkedAt) {
        return new IpProxyCheckResult(
                id, false, null, null, null, null, null, null,
                false, whatsappHttpStatus, whatsappErrorMessage,
                timing == null ? IpProxyCheckTiming.zero() : timing, checkedAt, errorMessage);
    }

    /** 构造没有阶段耗时的失败结果。 */
    public static IpProxyCheckResult failed(Long id, String errorMessage, Long checkedAt) {
        return failed(id, errorMessage, errorMessage, null, IpProxyCheckTiming.zero(), checkedAt);
    }
}
