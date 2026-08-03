package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskFilter;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupMarketingSetting;
import com.armada.task.model.entity.PullTaskGroupMarketingSummary;
import com.armada.task.model.enums.PullTaskGroupSource;
import com.armada.task.model.enums.PullTaskType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 使用 H2 MySQL 模式执行拉群任务统一列表的真实 Mapper XML。 */
@SpringJUnitConfig(PullTaskMapperInMemoryTest.TestMyBatisPlusConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskMapperInMemoryTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskMapper mapper;

    @Autowired
    private PullTaskGroupMarketingSummaryMapper summaryMapper;

    @Autowired
    private PullTaskGroupMarketingSettingMapper settingMapper;

    @BeforeEach
    void setUp() throws SQLException {
        executeSql("DROP ALL OBJECTS", """
                CREATE TABLE pull_task (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    task_type VARCHAR(32) NOT NULL,
                    group_source VARCHAR(32),
                    task_name VARCHAR(128) NOT NULL,
                    group_name VARCHAR(128),
                    mode VARCHAR(32) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    primary_stage VARCHAR(64),
                    blocking_reason VARCHAR(255),
                    started_at BIGINT,
                    finished_at BIGINT,
                    version INT NOT NULL DEFAULT 1,
                    group_count INT NOT NULL,
                    expected_pull_count INT NOT NULL,
                    operator_name VARCHAR(64),
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    last_business_executed_at BIGINT,
                    remark VARCHAR(500),
                    deleted_at BIGINT
                )
                """, """
                CREATE TABLE pull_task_group_marketing_summary (
                    tenant_id BIGINT NOT NULL,
                    task_id BIGINT NOT NULL,
                    target_group_count INT NOT NULL DEFAULT 0,
                    transfer_success_count INT NOT NULL DEFAULT 0,
                    transfer_pending_close_count INT NOT NULL DEFAULT 0,
                    transfer_partial_count INT NOT NULL DEFAULT 0,
                    transfer_failed_count INT NOT NULL DEFAULT 0,
                    transfer_running_count INT NOT NULL DEFAULT 0,
                    transfer_waiting_count INT NOT NULL DEFAULT 0,
                    planned_target_count INT NOT NULL DEFAULT 0,
                    effective_target_count INT NOT NULL DEFAULT 0,
                    joined_success_count INT NOT NULL DEFAULT 0,
                    already_in_group_count INT NOT NULL DEFAULT 0,
                    privacy_restricted_count INT NOT NULL DEFAULT 0,
                    invalid_number_count INT NOT NULL DEFAULT 0,
                    unregistered_count INT NOT NULL DEFAULT 0,
                    pull_result_unknown_count INT NOT NULL DEFAULT 0,
                    remaining_target_count INT NOT NULL DEFAULT 0,
                    marketing_waiting_count INT NOT NULL DEFAULT 0,
                    marketing_running_count INT NOT NULL DEFAULT 0,
                    marketing_paused_count INT NOT NULL DEFAULT 0,
                    marketing_completed_count INT NOT NULL DEFAULT 0,
                    marketing_abnormal_stopped_count INT NOT NULL DEFAULT 0,
                    message_success_count INT NOT NULL DEFAULT 0,
                    message_failed_count INT NOT NULL DEFAULT 0,
                    message_unknown_count INT NOT NULL DEFAULT 0,
                    abnormal_group_count INT NOT NULL DEFAULT 0,
                    puller_shortage_group_count INT NOT NULL DEFAULT 0,
                    banned_account_count INT NOT NULL DEFAULT 0,
                    available_puller_count INT NOT NULL DEFAULT 0,
                    is_target_data_shortage TINYINT NOT NULL DEFAULT 0,
                    is_puller_shortage TINYINT NOT NULL DEFAULT 0,
                    is_water_army_shortage TINYINT NOT NULL DEFAULT 0,
                    is_admin_shortage TINYINT NOT NULL DEFAULT 0,
                    is_marketing_admin_shortage TINYINT NOT NULL DEFAULT 0,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    PRIMARY KEY (tenant_id, task_id)
                )
                """, """
                CREATE TABLE pull_task_group_marketing_setting (
                    tenant_id BIGINT PRIMARY KEY,
                    marketing_silence_minutes INT NOT NULL,
                    group_lockdown_minutes INT NOT NULL,
                    max_marketing_accounts_per_group INT NOT NULL,
                    created_by BIGINT,
                    updated_by BIGINT,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL
                )
                """, """
                INSERT INTO pull_task VALUES
                  (11, 7, 'STANDARD', NULL, '普通任务甲', '普通群', 'OLD_LINK',
                   'WAIT_START', NULL, NULL, NULL, NULL, 1, 2, 100, '运营甲', 1000, 1000, NULL, NULL, NULL),
                  (12, 7, 'GROUP_MARKETING', 'HISTORICAL', '印度营销任务', '印度历史群',
                   'OLD_LINK', 'EXECUTING', '拉人中', NULL, NULL, NULL, 1, 5, 10000, '运营乙',
                   2000, 2100, 2050, '重点任务', NULL),
                  (13, 7, 'GROUP_MARKETING', 'MIXED', '混合营销任务', '巴西混合群',
                   'OLD_LINK', 'EXECUTING', '等待营销', NULL, NULL, NULL, 1, 3, 5000, '运营丙',
                   3000, 3100, NULL, NULL, NULL),
                  (14, 7, 'GROUP_MARKETING', 'HISTORICAL', '已删除印度任务', '印度群',
                   'OLD_LINK', 'EXECUTING', NULL, NULL, NULL, NULL, 1, 1, 10, '运营乙',
                   4000, 4100, NULL, NULL, 4200),
                  (21, 8, 'GROUP_MARKETING', 'HISTORICAL', '印度营销任务', '印度历史群',
                   'OLD_LINK', 'EXECUTING', NULL, NULL, NULL, NULL, 1, 5, 10000, '运营乙',
                   2000, 2100, NULL, NULL, NULL)
                """, """
                INSERT INTO pull_task_group_marketing_summary (
                    tenant_id, task_id, target_group_count, message_success_count,
                    message_failed_count, message_unknown_count, created_at, updated_at
                ) VALUES
                    (7, 12, 5, 9, 1, 0, 5000, 5000),
                    (8, 21, 6, 8, 2, 0, 5000, 5000)
                """);
        TenantContext.set(7L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void filtersMarketingTasksAndKeepsTenantRowsIsolated() {
        PullTaskFilter filter = new PullTaskFilter(
                null, "印度", "EXECUTING", PullTaskType.GROUP_MARKETING,
                PullTaskGroupSource.HISTORICAL, "乙");

        assertThat(mapper.countPage(filter)).isEqualTo(1);
        assertThat(mapper.selectPage(filter, 0, 10))
                .extracting(PullTask::getId)
                .containsExactly(12L);

        TenantContext.set(8L);
        assertThat(mapper.countPage(filter)).isEqualTo(1);
        assertThat(mapper.selectPage(filter, 0, 10))
                .extracting(PullTask::getId)
                .containsExactly(21L);
    }

    @Test
    void supportsExactFieldsLimitAndDescendingIdOrder() {
        assertThat(mapper.selectPage(new PullTaskFilter(
                11L, null, null, PullTaskType.STANDARD, null, null), 0, 10))
                .extracting(PullTask::getId)
                .containsExactly(11L);
        assertThat(mapper.countPage(new PullTaskFilter(
                null, null, null, PullTaskType.GROUP_MARKETING,
                PullTaskGroupSource.MIXED, null))).isEqualTo(1);
        assertThat(mapper.selectPage(new PullTaskFilter(
                null, null, null, null, null, null), 0, 2))
                .extracting(PullTask::getId)
                .containsExactly(13L, 12L);
    }

    @Test
    void readsSummaryInOneBatchWithoutCrossingTenantBoundary() {
        assertThat(summaryMapper.selectByTaskIds(java.util.List.of(12L, 21L)))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.getTaskId()).isEqualTo(12L);
                    assertThat(summary.getTargetGroupCount()).isEqualTo(5);
                    assertThat(summary.getMessageUnknownCount()).isZero();
                });

        TenantContext.set(8L);
        assertThat(summaryMapper.selectByTaskIds(java.util.List.of(12L, 21L)))
                .extracting(PullTaskGroupMarketingSummary::getTaskId)
                .containsExactly(21L);
    }

    @Test
    void upsertsSettingWithoutOverwritingAnotherTenantOrCreationAudit() {
        assertThat(settingMapper.selectCurrent()).isNull();

        PullTaskGroupMarketingSetting tenantSeven = setting(30, 60, 2, 99L, 5_000L);
        assertThat(settingMapper.upsert(tenantSeven)).isEqualTo(1);
        assertThat(settingMapper.selectCurrent())
                .satisfies(setting -> {
                    assertThat(setting.getMarketingSilenceMinutes()).isEqualTo(30);
                    assertThat(setting.getCreatedBy()).isEqualTo(99L);
                    assertThat(setting.getCreatedAt()).isEqualTo(5_000L);
                });

        PullTaskGroupMarketingSetting updated = setting(45, 90, 3, 101L, 6_000L);
        assertThat(settingMapper.upsert(updated)).isEqualTo(2);
        assertThat(settingMapper.selectCurrent())
                .satisfies(setting -> {
                    assertThat(setting.getMarketingSilenceMinutes()).isEqualTo(45);
                    assertThat(setting.getGroupLockdownMinutes()).isEqualTo(90);
                    assertThat(setting.getMaxMarketingAccountsPerGroup()).isEqualTo(3);
                    assertThat(setting.getCreatedBy()).isEqualTo(99L);
                    assertThat(setting.getCreatedAt()).isEqualTo(5_000L);
                    assertThat(setting.getUpdatedBy()).isEqualTo(101L);
                    assertThat(setting.getUpdatedAt()).isEqualTo(6_000L);
                });

        TenantContext.set(8L);
        assertThat(settingMapper.selectCurrent()).isNull();
        assertThat(settingMapper.upsert(setting(10, 20, 1, 202L, 7_000L))).isEqualTo(1);
        assertThat(settingMapper.selectCurrent().getMarketingSilenceMinutes()).isEqualTo(10);

        TenantContext.set(7L);
        assertThat(settingMapper.selectCurrent().getMarketingSilenceMinutes()).isEqualTo(45);
    }

    @Test
    void softDeletesOnlyStatusesAllowedForEachTaskType() throws SQLException {
        executeSql("""
                INSERT INTO pull_task VALUES
                  (15, 7, 'STANDARD', NULL, '已完成普通任务', NULL, 'OLD_LINK',
                   'COMPLETED', NULL, NULL, NULL, NULL, 1, 1, 10, '运营甲', 5000, 5000, NULL, NULL, NULL),
                  (16, 7, 'STANDARD', NULL, '执行中普通任务', NULL, 'OLD_LINK',
                   'EXECUTING', NULL, NULL, NULL, NULL, 1, 1, 10, '运营甲', 5000, 5000, NULL, NULL, NULL),
                  (17, 7, 'GROUP_MARKETING', 'HISTORICAL', '营销草稿', NULL, 'OLD_LINK',
                   'DRAFT', NULL, NULL, NULL, NULL, 1, 1, 10, '运营甲', 5000, 5000, NULL, NULL, NULL),
                  (18, 7, 'GROUP_MARKETING', 'HISTORICAL', '营销待开始', NULL, 'OLD_LINK',
                   'WAIT_START', NULL, NULL, NULL, NULL, 1, 1, 10, '运营甲', 5000, 5000, NULL, NULL, NULL)
                """);

        assertThat(mapper.batchSoftDeleteAllowed(
                java.util.List.of(11L, 15L, 16L, 17L, 18L), 9_000L)).isEqualTo(3);
        assertThat(mapper.selectPage(
                new PullTaskFilter(null, null, null, null, null, null), 0, 20))
                .extracting(PullTask::getId)
                .contains(16L, 18L)
                .doesNotContain(11L, 15L, 17L);

        TenantContext.set(8L);
        assertThat(mapper.selectPage(
                new PullTaskFilter(null, null, null, null, null, null), 0, 20))
                .extracting(PullTask::getId)
                .containsExactly(21L);
    }

    private static PullTaskGroupMarketingSetting setting(
            int silenceMinutes,
            int lockdownMinutes,
            int maxMarketingAccounts,
            long operatorId,
            long now) {
        PullTaskGroupMarketingSetting setting = new PullTaskGroupMarketingSetting();
        setting.setMarketingSilenceMinutes(silenceMinutes);
        setting.setGroupLockdownMinutes(lockdownMinutes);
        setting.setMaxMarketingAccountsPerGroup(maxMarketingAccounts);
        setting.setCreatedBy(operatorId);
        setting.setUpdatedBy(operatorId);
        setting.setCreatedAt(now);
        setting.setUpdatedAt(now);
        return setting;
    }

    private void executeSql(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** 测试专用 MyBatis-Plus 配置，复用生产租户插件并加载真实 Mapper XML。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestMyBatisPlusConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:pull_task_mapper_test"
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            h2.setUser("sa");
            h2.setPassword("");
            return h2;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor mybatisPlusInterceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);

            MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setConfiguration(configuration);
            factoryBean.setPlugins(mybatisPlusInterceptor);
            factoryBean.setMapperLocations(
                    new ClassPathResource("mapper/task/PullTaskMapper.xml"),
                    new ClassPathResource("mapper/task/PullTaskGroupMarketingSummaryMapper.xml"),
                    new ClassPathResource("mapper/task/PullTaskGroupMarketingSettingMapper.xml"));
            return factoryBean.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskMapper pullTaskMapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskMapper.class);
        }

        @Bean
        PullTaskGroupMarketingSummaryMapper pullTaskGroupMarketingSummaryMapper(
                SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskGroupMarketingSummaryMapper.class);
        }

        @Bean
        PullTaskGroupMarketingSettingMapper pullTaskGroupMarketingSettingMapper(
                SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskGroupMarketingSettingMapper.class);
        }
    }
}
