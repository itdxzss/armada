package com.armada.resource.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.resource.model.IpProxyStatus;
import com.armada.resource.model.ProxyOwnership;
import com.armada.resource.model.ProxyProtocol;
import com.armada.resource.model.entity.IpProxy;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * IP 代理 Mapper 真库测试。
 *
 * <p>覆盖账号上线需要的活跃代理读取、空闲代理锁定、绑定和释放 XML,
 * 确保 MyBatis XML 和租户拦截器能真实执行。</p>
 */
class IpProxyMapperDbTest extends DbTestBase {

    @Autowired
    private IpProxyMapper mapper;

    @Test
    void selectActiveById_returnsInsertedActiveProxy() {
        long now = System.currentTimeMillis();
        IpProxy proxy = newIdleProxy(now);
        mapper.insert(proxy);

        IpProxy found = mapper.selectActiveById(proxy.getId());

        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(proxy.getId());
        assertThat(found.getHost()).isEqualTo(proxy.getHost());
        assertThat(found.getProtocol()).isEqualTo(ProxyProtocol.SOCKS5.code());
    }

    @Test
    void bindingLifecycle_selectIdleMarkUsingAndReleaseByAccount() {
        long now = System.currentTimeMillis();
        IpProxy proxy = newIdleProxy(now);
        mapper.insert(proxy);

        IpProxy selected = mapper.selectOneIdleForUpdate(TEST_TENANT_ID, IpProxyStatus.IDLE.code());
        assertThat(selected).isNotNull();
        assertThat(selected.getId()).isEqualTo(proxy.getId());

        int marked = mapper.markUsingAndBind(
                selected.getId(),
                501L,
                IpProxyStatus.IDLE.code(),
                IpProxyStatus.IN_USE.code(),
                now + 1);
        assertThat(marked).isEqualTo(1);

        IpProxy bound = mapper.selectActiveById(proxy.getId());
        assertThat(bound.getStatus()).isEqualTo(IpProxyStatus.IN_USE.code());
        assertThat(bound.getBoundAccountId()).isEqualTo(501L);
        assertThat(bound.getBoundAt()).isEqualTo(now + 1);

        int released = mapper.releaseByAccount(
                501L,
                IpProxyStatus.IDLE.code(),
                IpProxyStatus.IN_USE.code(),
                now + 2);
        assertThat(released).isEqualTo(1);

        IpProxy idle = mapper.selectActiveById(proxy.getId());
        assertThat(idle.getStatus()).isEqualTo(IpProxyStatus.IDLE.code());
        assertThat(idle.getBoundAccountId()).isNull();
        assertThat(idle.getBoundAt()).isNull();
        assertThat(idle.getUpdatedAt()).isEqualTo(now + 2);
    }

    @Test
    void releaseOnlineAllocation_releasesOnlyMatchingProxyAndAccount() {
        long now = System.currentTimeMillis();
        IpProxy proxy = newIdleProxy(now);
        mapper.insert(proxy);
        mapper.markUsingAndBind(
                proxy.getId(),
                501L,
                IpProxyStatus.IDLE.code(),
                IpProxyStatus.IN_USE.code(),
                now + 1);

        int missed = mapper.releaseOnlineAllocation(
                502L,
                proxy.getId(),
                IpProxyStatus.IDLE.code(),
                IpProxyStatus.IN_USE.code(),
                now + 2);
        assertThat(missed).isZero();
        assertThat(mapper.selectActiveById(proxy.getId()).getBoundAccountId()).isEqualTo(501L);

        int released = mapper.releaseOnlineAllocation(
                501L,
                proxy.getId(),
                IpProxyStatus.IDLE.code(),
                IpProxyStatus.IN_USE.code(),
                now + 3);
        assertThat(released).isEqualTo(1);

        IpProxy idle = mapper.selectActiveById(proxy.getId());
        assertThat(idle.getStatus()).isEqualTo(IpProxyStatus.IDLE.code());
        assertThat(idle.getBoundAccountId()).isNull();
        assertThat(idle.getBoundAt()).isNull();
        assertThat(idle.getUpdatedAt()).isEqualTo(now + 3);
    }

    @Test
    void batchBindingLifecycle_selectIdleMarkUsingAndReleaseOnlineAllocations() {
        long now = System.currentTimeMillis();
        IpProxy proxyA = newIdleProxy(now);
        IpProxy proxyB = newIdleProxy(now + 1);
        mapper.insert(proxyA);
        mapper.insert(proxyB);

        List<IpProxy> selected = mapper.selectIdleForUpdate(TEST_TENANT_ID, IpProxyStatus.IDLE.code(), 2);
        assertThat(selected).hasSizeGreaterThanOrEqualTo(2);
        List<IpProxyBindTarget> targets = List.of(
                new IpProxyBindTarget(selected.get(0).getId(), 701L),
                new IpProxyBindTarget(selected.get(1).getId(), 702L));

        int marked = mapper.markUsingAndBindBatch(
                targets,
                IpProxyStatus.IDLE.code(),
                IpProxyStatus.IN_USE.code(),
                now + 2);
        assertThat(marked).isEqualTo(2);

        IpProxy boundA = mapper.selectActiveById(targets.get(0).proxyId());
        IpProxy boundB = mapper.selectActiveById(targets.get(1).proxyId());
        assertThat(boundA.getStatus()).isEqualTo(IpProxyStatus.IN_USE.code());
        assertThat(boundA.getBoundAccountId()).isEqualTo(701L);
        assertThat(boundB.getStatus()).isEqualTo(IpProxyStatus.IN_USE.code());
        assertThat(boundB.getBoundAccountId()).isEqualTo(702L);

        int missed = mapper.releaseOnlineAllocations(
                List.of(new IpProxyBindTarget(targets.get(0).proxyId(), 999L)),
                IpProxyStatus.IDLE.code(),
                IpProxyStatus.IN_USE.code(),
                now + 3);
        assertThat(missed).isZero();
        assertThat(mapper.selectActiveById(targets.get(0).proxyId()).getBoundAccountId()).isEqualTo(701L);

        int released = mapper.releaseOnlineAllocations(
                targets,
                IpProxyStatus.IDLE.code(),
                IpProxyStatus.IN_USE.code(),
                now + 4);
        assertThat(released).isEqualTo(2);
        assertThat(mapper.selectActiveById(targets.get(0).proxyId()).getStatus()).isEqualTo(IpProxyStatus.IDLE.code());
        assertThat(mapper.selectActiveById(targets.get(0).proxyId()).getBoundAccountId()).isNull();
        assertThat(mapper.selectActiveById(targets.get(1).proxyId()).getStatus()).isEqualTo(IpProxyStatus.IDLE.code());
        assertThat(mapper.selectActiveById(targets.get(1).proxyId()).getBoundAccountId()).isNull();
    }

    private static IpProxy newIdleProxy(long suffix) {
        IpProxy proxy = new IpProxy();
        proxy.setHost("proxy-" + suffix + ".internal");
        proxy.setPort(1080);
        proxy.setProtocol(ProxyProtocol.SOCKS5.code());
        proxy.setUsername("user" + suffix);
        proxy.setPassword("pass_session-Abc123" + suffix);
        proxy.setRegion("印度");
        proxy.setStatus(IpProxyStatus.IDLE.code());
        proxy.setSource("dbtest");
        proxy.setOwnership(ProxyOwnership.OWNED.code());
        proxy.setCreatedAt(suffix);
        proxy.setUpdatedAt(suffix);
        return proxy;
    }
}
