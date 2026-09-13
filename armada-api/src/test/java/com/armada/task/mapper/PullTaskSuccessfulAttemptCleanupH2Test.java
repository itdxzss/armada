package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskParticipantAggregateTransition;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.support.TransactionTemplate;

/** 迟到成功后的活动 attempt 清理必须保留胜出调用的事实和租户边界。 */
@SpringJUnitConfig(PullTaskSuccessfulAttemptCleanupH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskSuccessfulAttemptCleanupH2Test {

    private static final long EXECUTION_ID = 501L;
    private static final long MATERIAL_ID = 601L;
    private static final long STATION_ID = 701L;
    private static final long ACTIVE_ATTEMPT_ID = 901L;
    private static final long WINNING_CALL_ID = 801L;

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskMaterialMemberMapper materials;
    @Autowired private PullTaskGroupAccountMapper accounts;
    @Autowired private PullTaskGroupExecutionMapper executions;
    @Autowired private DataSourceTransactionManager transactionManager;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource,
                """
                INSERT INTO pull_task_group_execution
                  (id, tenant_id, task_id, seq, source_file_index, source_file_name,
                   created_at, updated_at)
                VALUES (501, 7, 100, 1, 1, 'members.txt', 100, 200)
                """,
                """
                INSERT INTO pull_task_material_member
                  (id, tenant_id, group_execution_id, member_seq, source_line_no,
                   normalized_phone, pull_status, pull_call_id, active_pull_attempt_id,
                   pull_failure_count, wa_jid, pull_reason_code, pull_reason_message,
                   pull_result_at, created_at, updated_at)
                VALUES (601, 7, 501, 1, 1, '8613900000001', 2, 801, 901,
                        2, '8613900000001@s.whatsapp.net', 'ROSTER_CONFIRMED',
                        '名单已确认进群', 180, 100, 200)
                """,
                """
                INSERT INTO pull_task_group_account
                  (id, tenant_id, task_id, group_execution_id, account_id, account_phone,
                   role_type, role_seq, membership_status, pull_call_id, active_pull_attempt_id,
                   membership_failure_count, membership_reason_code, membership_reason_message,
                   membership_result_at, joined_at, created_at, updated_at)
                VALUES (701, 7, 100, 501, 1001, '8613800000001', 3, 1, 2, 801, 901,
                        1, 'ROSTER_CONFIRMED', '名单已确认在群', 170, 160, 100, 200)
                """);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void materialCleanupPreservesWinningCallAndSuccessFact() {
        assertThat(materials.clearSuccessfulPullAttempt(
                scope(MATERIAL_ID, ACTIVE_ATTEMPT_ID), PullTaskMaterialPullStatus.SUCCESS.code()))
                .isEqualTo(1);

        assertThat(materials.selectByExecution(EXECUTION_ID)).singleElement().satisfies(saved -> {
            assertThat(saved.getActivePullAttemptId()).isNull();
            assertThat(saved.getPullStatus()).isEqualTo(PullTaskMaterialPullStatus.SUCCESS.code());
            assertThat(saved.getPullCallId()).isEqualTo(WINNING_CALL_ID);
            assertThat(saved.getPullFailureCount()).isEqualTo(2L);
            assertThat(saved.getWaJid()).isEqualTo("8613900000001@s.whatsapp.net");
            assertThat(saved.getPullReasonCode()).isEqualTo("ROSTER_CONFIRMED");
            assertThat(saved.getPullReasonMessage()).isEqualTo("名单已确认进群");
            assertThat(saved.getPullResultAt()).isEqualTo(180L);
        });
        assertThat(materials.clearSuccessfulPullAttempt(
                scope(MATERIAL_ID, ACTIVE_ATTEMPT_ID), PullTaskMaterialPullStatus.SUCCESS.code()))
                .isZero();
    }

    @Test
    void stationCleanupPreservesWinningCallAndMembershipFact() {
        assertThat(accounts.clearSuccessfulPullAttempt(
                scope(STATION_ID, ACTIVE_ATTEMPT_ID),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code())).isEqualTo(1);

        assertThat(accounts.selectById(STATION_ID)).satisfies(saved -> {
            assertThat(saved.getActivePullAttemptId()).isNull();
            assertThat(saved.getMembershipStatus())
                    .isEqualTo(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
            assertThat(saved.getPullCallId()).isEqualTo(WINNING_CALL_ID);
            assertThat(saved.getMembershipFailureCount()).isEqualTo(1L);
            assertThat(saved.getMembershipReasonCode()).isEqualTo("ROSTER_CONFIRMED");
            assertThat(saved.getMembershipReasonMessage()).isEqualTo("名单已确认在群");
            assertThat(saved.getMembershipResultAt()).isEqualTo(170L);
            assertThat(saved.getJoinedAt()).isEqualTo(160L);
        });
        assertThat(accounts.clearSuccessfulPullAttempt(
                scope(STATION_ID, ACTIVE_ATTEMPT_ID),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code())).isZero();
    }

    @Test
    void differentAttemptAndDifferentTenantCannotClearTheActivePointer() {
        assertThat(materials.clearSuccessfulPullAttempt(
                scope(MATERIAL_ID, ACTIVE_ATTEMPT_ID - 1),
                PullTaskMaterialPullStatus.SUCCESS.code())).isZero();
        assertThat(accounts.clearSuccessfulPullAttempt(
                scope(STATION_ID, ACTIVE_ATTEMPT_ID - 1),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code())).isZero();

        TenantContext.set(8L);
        assertThat(materials.clearSuccessfulPullAttempt(
                scope(MATERIAL_ID, ACTIVE_ATTEMPT_ID),
                PullTaskMaterialPullStatus.SUCCESS.code())).isZero();
        assertThat(accounts.clearSuccessfulPullAttempt(
                scope(STATION_ID, ACTIVE_ATTEMPT_ID),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code())).isZero();

        TenantContext.set(7L);
        assertThat(materials.selectByExecution(EXECUTION_ID).get(0).getActivePullAttemptId())
                .isEqualTo(ACTIVE_ATTEMPT_ID);
        assertThat(accounts.selectById(STATION_ID).getActivePullAttemptId())
                .isEqualTo(ACTIVE_ATTEMPT_ID);
    }

    @Test
    void unresolvedParticipantsKeepTheirActivePointer() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE pull_task_material_member SET pull_status=? WHERE id=?",
                PullTaskMaterialPullStatus.UNKNOWN.code(), MATERIAL_ID);
        jdbc.update("UPDATE pull_task_group_account SET membership_status=? WHERE id=?",
                PullTaskGroupAccountMembershipStatus.UNKNOWN.code(), STATION_ID);

        assertThat(materials.clearSuccessfulPullAttempt(
                scope(MATERIAL_ID, ACTIVE_ATTEMPT_ID),
                PullTaskMaterialPullStatus.SUCCESS.code())).isZero();
        assertThat(accounts.clearSuccessfulPullAttempt(
                scope(STATION_ID, ACTIVE_ATTEMPT_ID),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code())).isZero();
        assertThat(materials.selectByExecution(EXECUTION_ID).get(0).getActivePullAttemptId())
                .isEqualTo(ACTIVE_ATTEMPT_ID);
        assertThat(accounts.selectById(STATION_ID).getActivePullAttemptId())
                .isEqualTo(ACTIVE_ATTEMPT_ID);
    }

    @Test
    void lockedExecutionReadAppliesTenantIsolationInsideTheTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        PullTaskGroupExecution owned = transaction.execute(status ->
                executions.selectByIdForUpdate(EXECUTION_ID));
        assertThat(owned).isNotNull();
        assertThat(owned.getId()).isEqualTo(EXECUTION_ID);

        TenantContext.set(8L);
        PullTaskGroupExecution foreign = transaction.execute(status ->
                executions.selectByIdForUpdate(EXECUTION_ID));
        assertThat(foreign).isNull();
    }

    private PullTaskParticipantAggregateTransition.Scope scope(long id, long attemptId) {
        return new PullTaskParticipantAggregateTransition.Scope(id, attemptId, 300L);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_successful_attempt_cleanup");
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskMaterialMemberMapper.xml",
                    "mapper/task/PullTaskGroupAccountMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        PullTaskMaterialMemberMapper materialMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMaterialMemberMapper.class);
        }

        @Bean
        PullTaskGroupAccountMapper accountMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupAccountMapper.class);
        }

        @Bean
        PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }
    }
}
