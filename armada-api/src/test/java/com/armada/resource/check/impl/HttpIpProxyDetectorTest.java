package com.armada.resource.check.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.resource.check.IpProxyCheckProperties;
import com.armada.resource.check.IpProxyCheckRequest;
import com.armada.resource.check.IpProxyCheckResult;
import com.armada.resource.model.ProxyProtocol;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * HTTP IP 代理真实检测适配器单测:解析用固定 JSON,失败映射只连本地不可达端口,不访问公网。
 */
class HttpIpProxyDetectorTest {

    @Test
    void parsePlainIp_trimsPlainTextIp() throws Exception {
        assertThat(HttpIpProxyDetector.parsePlainIp(" 68.187.236.156\n")).isEqualTo("68.187.236.156");
    }

    @Test
    void parsePlainIp_rejectsBlankOrHtmlBody() {
        assertThatThrownBy(() -> HttpIpProxyDetector.parsePlainIp(" "))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("出口 IP 响应为空");
        assertThatThrownBy(() -> HttpIpProxyDetector.parsePlainIp("<html>bad</html>"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("出口 IP 响应非法");
    }

    @Test
    void isWhatsappStatusAcceptable_acceptsAnyExplicitHttpStatusFromWhatsapp() {
        assertThat(HttpIpProxyDetector.isWhatsappStatusAcceptable(200)).isTrue();
        assertThat(HttpIpProxyDetector.isWhatsappStatusAcceptable(301)).isTrue();
        assertThat(HttpIpProxyDetector.isWhatsappStatusAcceptable(400)).isTrue();
        assertThat(HttpIpProxyDetector.isWhatsappStatusAcceptable(503)).isTrue();
        assertThat(HttpIpProxyDetector.isWhatsappStatusAcceptable(null)).isFalse();
    }

    @Test
    void parseIpApiJson_extractsOutboundIpCountryAndLocationFields() {
        String json = """
                {
                  "status": "success",
                  "query": "103.10.10.10",
                  "countryCode": "IN",
                  "regionName": "Maharashtra",
                  "city": "Mumbai",
                  "isp": "Example ISP",
                  "lat": 19.076,
                  "lon": 72.8777
                }
                """;

        IpProxyCheckResult result = HttpIpProxyDetector.parseIpApiJson(10L, json, 1_719_800_000_000L);

        assertThat(result.success()).isTrue();
        assertThat(result.id()).isEqualTo(10L);
        assertThat(result.outboundIp()).isEqualTo("103.10.10.10");
        assertThat(result.countryCode()).isEqualTo("IN");
        assertThat(result.location()).isEqualTo("Mumbai, Maharashtra");
        assertThat(result.isp()).isEqualTo("Example ISP");
        assertThat(result.latitude()).isEqualByComparingTo(new BigDecimal("19.076"));
        assertThat(result.longitude()).isEqualByComparingTo(new BigDecimal("72.8777"));
        assertThat(result.checkedAt()).isEqualTo(1_719_800_000_000L);
    }

    @Test
    void check_connectionFailureReturnsFailedResultWithoutLeakingPassword() {
        HttpIpProxyDetector detector = new HttpIpProxyDetector(new IpProxyCheckProperties());
        IpProxyCheckRequest request = new IpProxyCheckRequest(
                10L,
                ProxyProtocol.HTTP.code(),
                "127.0.0.1",
                9,
                "user-secret",
                "pass-secret");

        IpProxyCheckResult result = detector.check(request);

        assertThat(result.success()).isFalse();
        assertThat(result.id()).isEqualTo(10L);
        assertThat(result.errorMessage()).isNotBlank();
        assertThat(result.errorMessage()).doesNotContain("pass-secret");
        assertThat(result.errorMessage()).doesNotContain("user-secret");
        assertThat(result.checkedAt()).isPositive();
    }

    @Test
    void check_socks5ProxyReturnsUnsupportedWithoutNetworkProbe() {
        HttpIpProxyDetector detector = new HttpIpProxyDetector(new IpProxyCheckProperties());
        IpProxyCheckRequest request = new IpProxyCheckRequest(
                10L,
                ProxyProtocol.SOCKS5.code(),
                "192.0.2.1",
                1080,
                "user-secret",
                "pass-secret");

        IpProxyCheckResult result = detector.check(request);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("当前仅支持HTTP代理检测");
        assertThat(result.errorMessage()).doesNotContain("pass-secret");
        assertThat(result.errorMessage()).doesNotContain("user-secret");
        assertThat(result.timing().egressMs()).isZero();
    }
}
