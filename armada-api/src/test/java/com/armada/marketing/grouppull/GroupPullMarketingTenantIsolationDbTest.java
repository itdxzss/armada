package com.armada.marketing.grouppull;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.shared.tenant.TenantContext;
import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 拉群营销 Mapper 租户隔离真库测试。 */
class GroupPullMarketingTenantIsolationDbTest extends DbTestBase {

    @Autowired
    private GroupPullMarketingMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void taskExtensionCannotBeReadFromAnotherTenant() {
        long taskId = Math.abs(System.nanoTime());
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO group_pull_marketing_task
                    (marketing_task_id, tenant_id, builder_group_id,
                     marketing_account_group_limit, friend_retry_limit, material_per_group,
                     speak_permission, builder_exit_enabled, block_reason, resource_status,
                     created_at, updated_at)
                VALUES (?, ?, 101, 10, 2, 3, 1, 1, 0, 1, ?, ?)
                """, taskId, TEST_TENANT_ID, now, now);

        assertThat(mapper.selectTaskById(taskId)).isNotNull();
        try {
            TenantContext.set(TEST_TENANT_ID + 1);
            assertThat(mapper.selectTaskById(taskId)).isNull();
        } finally {
            TenantContext.set(TEST_TENANT_ID);
        }
    }
}
