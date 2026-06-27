package com.armada.resource.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.resource.model.IpProxyStatus;
import com.armada.resource.model.ProxyOwnership;
import com.armada.resource.model.ProxyProtocol;
import com.armada.resource.model.entity.IpProxy;
import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * IP 代理 Mapper 真库测试。
 *
 * <p>覆盖账号手动上线需要的按 proxyId 查活跃代理 XML,确保 MyBatis XML 和租户拦截器能真实执行。</p>
 */
class IpProxyMapperDbTest extends DbTestBase {

    @Autowired
    private IpProxyMapper mapper;

    @Test
    void selectActiveById_returnsInsertedActiveProxy() {
        long now = System.currentTimeMillis();
        IpProxy proxy = new IpProxy();
        proxy.setHost("proxy-" + now + ".internal");
        proxy.setPort(1080);
        proxy.setProtocol(ProxyProtocol.SOCKS5.code());
        proxy.setUsername("user" + now);
        proxy.setPassword("pass_session-Abc123" + now);
        proxy.setRegion("印度");
        proxy.setStatus(IpProxyStatus.IDLE.code());
        proxy.setSource("dbtest");
        proxy.setOwnership(ProxyOwnership.OWNED.code());
        proxy.setCreatedAt(now);
        proxy.setUpdatedAt(now);
        mapper.insert(proxy);

        IpProxy found = mapper.selectActiveById(proxy.getId());

        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(proxy.getId());
        assertThat(found.getHost()).isEqualTo(proxy.getHost());
        assertThat(found.getProtocol()).isEqualTo(ProxyProtocol.SOCKS5.code());
    }
}
