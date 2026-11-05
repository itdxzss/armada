package com.armada.account.tiercheck;

import com.armada.account.mapper.AccountTierCheckMapper;
import com.armada.account.model.dto.AccountTierCheckClaim;
import com.armada.account.model.dto.AccountTierCheckWork;
import com.armada.account.service.AccountTierCheckProperties;
import com.armada.account.service.AccountTierCheckScheduler;
import com.armada.account.service.AccountTierCheckTransactions;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolAccountIntegrity;
import com.armada.platform.protocol.port.AccountIntegrityPort;
import com.armada.shared.tenant.TenantContext;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountTierCheckSchedulerTest {
    @AfterEach void clear() { TenantContext.clear(); }
    @Configuration(proxyBeanMethods = false) @Import(AccountTierCheckScheduler.class)
    static class OnlyScheduler {}

    @Test void springDoesNotCreateSchedulerWhenFlagIsMissingOrFalse() {
        var context = new ApplicationContextRunner().withUserConfiguration(OnlyScheduler.class);
        context.run(c -> assertThat(c).doesNotHaveBean(AccountTierCheckScheduler.class));
        context.withPropertyValues("armada.tier-check.enabled=false").run(c -> assertThat(c).doesNotHaveBean(AccountTierCheckScheduler.class));
    }
    @Test void defaultOffNeverTouchesDatabaseOrProtocol() {
        var mapper = mock(AccountTierCheckMapper.class); var tx = mock(AccountTierCheckTransactions.class); var port = mock(AccountIntegrityPort.class);
        var props = new AccountTierCheckProperties(); assertThat(props.isEnabled()).isFalse();
        var scheduler = new AccountTierCheckScheduler(mapper, tx, port, props);
        try { scheduler.tick(); verifyNoInteractions(mapper, tx, port); } finally { scheduler.close(); }
    }
    @Test void blockingHttpDoesNotBlockTickAndPoolCapacityBoundsClaims() throws Exception {
        var mapper = mock(AccountTierCheckMapper.class); var tx = mock(AccountTierCheckTransactions.class); var port = mock(AccountIntegrityPort.class);
        var props = new AccountTierCheckProperties(); props.setEnabled(true); props.setPoolSize(1);
        var first = new AccountTierCheckWork(7L, 1L, 1L); var second = new AccountTierCheckWork(8L, 2L, 2L);
        var claim = new AccountTierCheckClaim(first, 1, new ProtocolAccountRef(1L, ProtocolBackend.WEB, "fixture", "00000000001"), "fixture", "00000000002");
        when(mapper.recoverable(anyLong(), anyInt())).thenReturn(List.of());
        when(mapper.scan(anyLong(), anyInt())).thenReturn(List.of(first, second));
        when(tx.claim(any(), anyLong())).thenAnswer(inv -> {
            assertThat(TenantContext.get()).isEqualTo(7L); return Optional.of(claim);
        });
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(port.query(any(), anyString())).thenAnswer(inv -> {
            assertThat(TenantContext.get()).isEqualTo(7L); assertThat(Thread.currentThread().getName()).startsWith("tier-check-query-");
            started.countDown(); assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            return ProtocolAccountIntegrity.failure("TIMEOUT");
        });
        var scheduler = new AccountTierCheckScheduler(mapper, tx, port, props);
        try {
            TenantContext.set(99L);
            // 主线程直接调用 tick，超时由独立计时器断言；HTTP 等待不应占据 tick。
            long start = System.nanoTime(); scheduler.tick();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue(); assertThat(TenantContext.get()).isEqualTo(99L);
            scheduler.tick(); verify(tx).claim(org.mockito.ArgumentMatchers.eq(first), anyLong());
            org.mockito.Mockito.verify(tx, org.mockito.Mockito.never()).claim(org.mockito.ArgumentMatchers.eq(second), anyLong());
            release.countDown(); verify(tx, timeout(2000)).apply(org.mockito.ArgumentMatchers.eq(claim), any(), anyLong());
        } finally { release.countDown(); scheduler.close(); }
    }
}
