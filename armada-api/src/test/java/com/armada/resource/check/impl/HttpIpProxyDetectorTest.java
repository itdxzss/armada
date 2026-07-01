package com.armada.resource.check.impl;

import static org.assertj.core.api.Assertions.assertThat;

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
        HttpIpProxyDetector detector = new HttpIpProxyDetector();
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
}
