package com.armada.marketing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.mapper.AccountGroupMapper;
import com.armada.marketing.model.enums.MarketingBusinessType;
import com.armada.marketing.service.impl.MarketingGroupOccupancyService;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 营销分组整组占用服务单元测试。 */
class MarketingGroupOccupancyServiceTest {

    @Test
    void lockAndReleaseMapExactlyOneAffectedRowToSuccess() {
        AtomicReference<Object[]> lockArgs = new AtomicReference<>();
        AtomicReference<Object[]> releaseArgs = new AtomicReference<>();
        MarketingGroupOccupancyService service = new MarketingGroupOccupancyService(
                mapper(1, 1, lockArgs, releaseArgs));

        assertThat(service.tryLock(11L, MarketingBusinessType.GROUP_PULL, 101L, 1_000L)).isTrue();
        assertThat(service.release(11L, MarketingBusinessType.GROUP_PULL, 101L, 2_000L)).isTrue();

        assertThat(lockArgs.get()).containsExactly(11L, 2, 101L, 1_000L);
        assertThat(releaseArgs.get()).containsExactly(11L, 2, 101L, 2_000L);
    }

    @Test
    void zeroAffectedRowsMeansLockOwnershipWasNotAcquired() {
        MarketingGroupOccupancyService service = new MarketingGroupOccupancyService(
                mapper(0, 0, new AtomicReference<>(), new AtomicReference<>()));

        assertThat(service.tryLock(11L, MarketingBusinessType.ORDINARY, 102L, 1_000L)).isFalse();
        assertThat(service.release(11L, MarketingBusinessType.ORDINARY, 102L, 2_000L)).isFalse();
    }

    private AccountGroupMapper mapper(int lockResult,
                                      int releaseResult,
                                      AtomicReference<Object[]> lockArgs,
                                      AtomicReference<Object[]> releaseArgs) {
        return (AccountGroupMapper) Proxy.newProxyInstance(
                AccountGroupMapper.class.getClassLoader(),
                new Class<?>[]{AccountGroupMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "tryLockMarketingOccupancy" -> {
                        lockArgs.set(args);
                        yield lockResult;
                    }
                    case "releaseMarketingOccupancy" -> {
                        releaseArgs.set(args);
                        yield releaseResult;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
