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

    private static final String MIXED_REGION = "混合（不限国家）";

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

        IpProxy selected = mapper.selectOneIdleByRegionPriorityForUpdate(
                TEST_TENANT_ID,
                IpProxyStatus.IDLE.code(),
                proxy.getRegion(),
                MIXED_REGION,
                List.of());
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

        IpProxy selectedA = mapper.selectOneIdleByRegionPriorityForUpdate(
                TEST_TENANT_ID,
                IpProxyStatus.IDLE.code(),
                proxyA.getRegion(),
                MIXED_REGION,
                List.of());
        IpProxy selectedB = mapper.selectOneIdleByRegionPriorityForUpdate(
                TEST_TENANT_ID,
                IpProxyStatus.IDLE.code(),
                proxyB.getRegion(),
                MIXED_REGION,
                List.of(selectedA.getId()));
        List<IpProxyBindTarget> targets = List.of(
                new IpProxyBindTarget(selectedA.getId(), 701L),
                new IpProxyBindTarget(selectedB.getId(), 702L));

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

    @Test
    void selectOneIdleByRegionPriorityForUpdate_skipsExcludedProxyIds() {
        long now = System.currentTimeMillis();
        IpProxy excluded = newIdleProxy(now);
        IpProxy candidate = newIdleProxy(now + 1);
        mapper.insert(excluded);
        mapper.insert(candidate);

        IpProxy selected = mapper.selectOneIdleByRegionPriorityForUpdate(
                TEST_TENANT_ID,
                IpProxyStatus.IDLE.code(),
                excluded.getRegion(),
                MIXED_REGION,
                List.of(excluded.getId()));

        assertThat(selected.getId()).isEqualTo(candidate.getId());
    }

    @Test
    void selectOneIdleByRegionPriorityForUpdate_prefersRequestedRegionThenMixedThenOther() {
        long now = System.currentTimeMillis();
        String preferredRegion = "优先国家-" + now;
        IpProxy other = newIdleProxy(now);
        other.setRegion("其它国家-" + now);
        IpProxy mixed = newIdleProxy(now + 1);
        mixed.setRegion(MIXED_REGION);
        IpProxy preferred = newIdleProxy(now + 2);
        preferred.setRegion(preferredRegion);
        mapper.insert(other);
        mapper.insert(mixed);
        mapper.insert(preferred);

        IpProxy selected = mapper.selectOneIdleByRegionPriorityForUpdate(
                TEST_TENANT_ID,
                IpProxyStatus.IDLE.code(),
                preferredRegion,
                MIXED_REGION,
                List.of());
        assertThat(selected.getId()).isEqualTo(preferred.getId());

        IpProxy fallback = mapper.selectOneIdleByRegionPriorityForUpdate(
                TEST_TENANT_ID,
                IpProxyStatus.IDLE.code(),
                preferredRegion,
                MIXED_REGION,
                List.of(preferred.getId()));
        assertThat(fallback.getRegion()).isEqualTo(MIXED_REGION);
    }

    @Test
    void selectBoundAccountIdsByProxyIds_returnsOnlyUsingBoundAccounts() {
        long now = System.currentTimeMillis();
        IpProxy bound = newIdleProxy(now);
        IpProxy idle = newIdleProxy(now + 1);
        mapper.insert(bound);
        mapper.insert(idle);
        mapper.markUsingAndBind(
                bound.getId(),
                801L,
                IpProxyStatus.IDLE.code(),
                IpProxyStatus.IN_USE.code(),
                now + 2);

        List<Long> accountIds = mapper.selectBoundAccountIdsByProxyIds(
                List.of(bound.getId(), idle.getId()),
                IpProxyStatus.IN_USE.code());

        assertThat(accountIds).containsExactly(801L);
    }

    @Test
    void selectDistinctRegions_deduplicatesBlankExcludedAndMixedFirst() {
        long now = System.currentTimeMillis();
        IpProxy india = newIdleProxy(now);
        india.setRegion("印度");
        IpProxy mixed = newIdleProxy(now + 1);
        mixed.setRegion(MIXED_REGION);
        IpProxy indiaAgain = newIdleProxy(now + 2);
        indiaAgain.setRegion("印度");
        IpProxy malaysia = newIdleProxy(now + 3);
        malaysia.setRegion("马来西亚");
        IpProxy blank = newIdleProxy(now + 4);
        blank.setRegion(" ");
        mapper.insert(india);
        mapper.insert(mixed);
        mapper.insert(indiaAgain);
        mapper.insert(malaysia);
        mapper.insert(blank);

        List<String> regions = mapper.selectDistinctRegions(MIXED_REGION);

        assertThat(regions).containsExactly(MIXED_REGION, "印度", "马来西亚");
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
