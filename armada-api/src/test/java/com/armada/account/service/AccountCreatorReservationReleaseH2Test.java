package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.account.mapper.AccountCreatorDeletionMapper;
import com.armada.account.service.impl.AccountCreatorDeletionServiceImpl;
import com.armada.account.takeover.AccountTakeoverH2Support;
import com.armada.shared.tenant.TenantContext;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;

/** 建群人放弃时只解除尚未开始注销的本执行行预留；使用真实 SQL 与独立事务。 */
class AccountCreatorReservationReleaseH2Test {
    private AccountTakeoverH2Support h;
    private AccountCreatorDeletionService service;

    @BeforeEach
    void setUp() throws Exception {
        h = new AccountTakeoverH2Support();
        var mapper = new SqlSessionTemplate(h.sqlSessionFactory).getMapper(AccountCreatorDeletionMapper.class);
        service = h.transactional(new AccountCreatorDeletionServiceImpl(mapper), AccountCreatorDeletionService.class);
        h.account(1, 6, 2, 1, null);
        h.reserve(1);
    }

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void releasesOnlyTheMatchingReservationAndPreservesAccountFacts() {
        assertThat(service.releaseReservation(11, 111)).isTrue();
        assertThat(service.releaseReservation(11, 111)).isFalse();
        assertThat(reservationCount()).isZero();
        assertThat(h.jdbc.queryForObject("SELECT COUNT(*) FROM account", Integer.class)).isOne();
        assertThat(h.jdbc.queryForObject("SELECT account_state FROM account_state", Integer.class)).isEqualTo(6);
        assertThat(h.jdbc.queryForObject("SELECT desired_login_state FROM account_state", Integer.class)).isEqualTo(1);
    }

    @Test
    void wrongTaskExecutionOrTenantCannotReleaseTheOwner() {
        assertThat(service.releaseReservation(12, 111)).isFalse();
        assertThat(service.releaseReservation(11, 112)).isFalse();
        TenantContext.set(2L);
        assertThat(service.releaseReservation(11, 111)).isFalse();
        assertThat(reservationCount()).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETING", "DELETED"})
    void irreversibleDeletionLifecyclesAreNeverReleased(String lifecycle) {
        h.jdbc.update("UPDATE account_creator_deletion SET lifecycle=?", lifecycle);
        assertThat(service.releaseReservation(11, 111)).isFalse();
        assertThat(reservationCount()).isOne();
        assertThat(h.jdbc.queryForObject("SELECT lifecycle FROM account_creator_deletion", String.class))
                .isEqualTo(lifecycle);
    }

    @Test
    void failedTaskTransactionRollsBackReservationRelease() {
        assertThatThrownBy(() -> h.transactions.executeWithoutResult(status -> {
            assertThat(service.releaseReservation(11, 111)).isTrue();
            throw new IllegalStateException("task termination failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(reservationCount()).isOne();
    }

    @Test
    void concurrentDeletionTransitionWinsOverWaitingRelease() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var deletion = executor.submit(() -> {
                TenantContext.set(1L);
                try {
                    h.transactions.executeWithoutResult(status -> {
                        h.jdbc.update("UPDATE account_creator_deletion SET lifecycle='DELETING' WHERE account_id=1");
                        locked.countDown();
                        try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                        catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                    });
                } finally { TenantContext.clear(); }
            });
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var freeing = executor.submit(() -> {
                TenantContext.set(1L);
                try { return service.releaseReservation(11, 111); }
                finally { TenantContext.clear(); }
            });
            assertThatThrownBy(() -> freeing.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            deletion.get(5, TimeUnit.SECONDS);
            assertThat(freeing.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(h.jdbc.queryForObject("SELECT lifecycle FROM account_creator_deletion", String.class))
                    .isEqualTo("DELETING");
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private int reservationCount() {
        return h.jdbc.queryForObject("SELECT COUNT(*) FROM account_creator_deletion", Integer.class);
    }
}
