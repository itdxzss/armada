package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.shared.tenant.TenantContext;
import com.armada.testsupport.DbTestBase;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 账号同步群入口登记真库测试：验证租户内 URL 唯一键并发 upsert。 */
class GroupLinkRegistryServiceDbTest extends DbTestBase {

    @Autowired
    private GroupLinkRegistryService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentAccountObservationReturnsOneSharedGroupLinkWithoutDuplicateKey() throws Exception {
        String groupJid = "120363concurrent-upsert-" + System.nanoTime() + "@g.us";
        String linkUrl = "wa://group/" + groupJid;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Long> first = executor.submit(() -> registerAfterStart(groupJid, ready, start));
            Future<Long> second = executor.submit(() -> registerAfterStart(groupJid, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Long firstId = first.get(10, TimeUnit.SECONDS);
            Long secondId = second.get(10, TimeUnit.SECONDS);
            assertThat(firstId).isEqualTo(secondId);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM group_link WHERE tenant_id = ? AND link_url = ?",
                    Integer.class,
                    TEST_TENANT_ID,
                    linkUrl)).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            jdbc.update("DELETE FROM group_link WHERE tenant_id = ? AND link_url = ?",
                    TEST_TENANT_ID, linkUrl);
        }
    }

    private Long registerAfterStart(String groupJid,
                                    CountDownLatch ready,
                                    CountDownLatch start) throws InterruptedException {
        TenantContext.set(TEST_TENANT_ID);
        try {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发群入口登记未在限定时间内开始");
            }
            return service.registerAccountObservedGroup(groupJid, "并发登记群", System.currentTimeMillis());
        } finally {
            TenantContext.clear();
        }
    }
}
