package com.armada.resource.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.resource.model.IpProxyStatus;
import com.armada.resource.model.ProxyOwnership;
import com.armada.resource.model.ProxyProtocol;
import com.armada.resource.model.entity.IpProxy;
import com.armada.resource.model.vo.IpProxyVO;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/**
 * IP 代理 VO 转换测试。
 */
class IpProxyConverterTest {

    private final IpProxyConverter converter = Mappers.getMapper(IpProxyConverter.class);

    @Test
    void toVO_setsValidAccountCountFromBoundAccountId() {
        IpProxy idle = proxy(null);
        IpProxy bound = proxy(100L);

        IpProxyVO idleVo = converter.toVO(idle);
        IpProxyVO boundVo = converter.toVO(bound);

        assertThat(idleVo.validAccountCount()).isZero();
        assertThat(boundVo.validAccountCount()).isEqualTo(1);
    }

    @Test
    void toVO_keepsIpIdentityFieldsUnmasked() {
        IpProxyVO vo = converter.toVO(proxy(null));

        assertThat(vo.region()).isEqualTo("印度");
        assertThat(vo.username()).isEqualTo("user");
        assertThat(vo.password()).isEqualTo("secret_session-Abc123");
        assertThat(vo.protocolLabel()).isEqualTo("SOCKS5");
        assertThat(vo.source()).isEqualTo("dbtest");
    }

    @Test
    void toVO_mapsAllocationAndDetectionFieldsForListPage() {
        IpProxyVO vo = converter.toVO(proxy(100L));

        assertThat(vo.allocationMode()).isEqualTo("mixed");
        assertThat(vo.allocationModeLabel()).isEqualTo("混合分组");
        assertThat(vo.lastSampleCheckAt()).isEqualTo(1_719_800_000_000L);
        assertThat(vo.detectedCountryCode()).isEqualTo("IN");
        assertThat(vo.outboundIp()).isEqualTo("103.10.10.10");
        assertThat(vo.detectedLocation()).isEqualTo("Mumbai, Maharashtra");
        assertThat(vo.detectedIsp()).isEqualTo("Example ISP");
        assertThat(vo.detectedLatitude()).isEqualByComparingTo("19.0760000");
        assertThat(vo.detectedLongitude()).isEqualByComparingTo("72.8777000");
        assertThat(vo.checkFailCount()).isEqualTo(2);
        assertThat(vo.lastCheckError()).isEqualTo("previous timeout");
    }

    private static IpProxy proxy(Long boundAccountId) {
        IpProxy proxy = new IpProxy();
        proxy.setId(1L);
        proxy.setHost("proxy.internal");
        proxy.setPort(1080);
        proxy.setProtocol(ProxyProtocol.SOCKS5.code());
        proxy.setUsername("user");
        proxy.setPassword("secret_session-Abc123");
        proxy.setRegion("印度");
        proxy.setStatus(IpProxyStatus.IDLE.code());
        proxy.setBoundAccountId(boundAccountId);
        proxy.setSource("dbtest");
        proxy.setOwnership(ProxyOwnership.OWNED.code());
        proxy.setAllocationMode("mixed");
        proxy.setLastSampleCheckAt(1_719_800_000_000L);
        proxy.setDetectedCountryCode("IN");
        proxy.setOutboundIp("103.10.10.10");
        proxy.setDetectedLocation("Mumbai, Maharashtra");
        proxy.setDetectedIsp("Example ISP");
        proxy.setDetectedLatitude(new BigDecimal("19.0760000"));
        proxy.setDetectedLongitude(new BigDecimal("72.8777000"));
        proxy.setCheckFailCount(2);
        proxy.setLastCheckError("previous timeout");
        proxy.setCreatedAt(1_700_000_000_000L);
        return proxy;
    }
}
