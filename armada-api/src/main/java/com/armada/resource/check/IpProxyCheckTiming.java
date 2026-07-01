package com.armada.resource.check;

/**
 * IP 代理检测阶段耗时,单位毫秒。未知阶段用 0。
 *
 * @param totalMs            单次检测总耗时
 * @param egressMs           获取出口 IP 耗时
 * @param geoMs              出口 IP 地理信息解析耗时
 * @param whatsappConnectMs  建立 WhatsApp 代理隧道耗时
 * @param whatsappProbeMs    WhatsApp 官方响应探测耗时
 */
public record IpProxyCheckTiming(
        long totalMs,
        long egressMs,
        long geoMs,
        long whatsappConnectMs,
        long whatsappProbeMs) {

    /** 全部阶段未知时使用的零耗时对象。 */
    public static IpProxyCheckTiming zero() {
        return new IpProxyCheckTiming(0, 0, 0, 0, 0);
    }
}
