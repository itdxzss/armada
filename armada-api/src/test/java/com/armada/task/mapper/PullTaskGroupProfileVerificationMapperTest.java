package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskGroupCreateTransition;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupCreateStep;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 使用真实 Mapper 和生产租户插件，验证必填资料核验证据与步骤推进不可分开提交。 */
@SpringJUnitConfig(PullTaskGroupProfileVerificationMapperTest.TestConfig.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class, inheritListeners = false)
class PullTaskGroupProfileVerificationMapperTest {

    private static final long EXECUTION_ID = 11L;
    private static final long TENANT_ID = 7L;
    private static final long NOW = 1_000L;
    private static final String COMMAND_ID = "cmd-profile-verified";

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskGroupExecutionMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    @BeforeEach
    void prepareSchema() throws SQLException {
        TenantContext.set(TENANT_ID);
        PullTaskNormalLinkH2Support.resetSchema(dataSource, """
                INSERT INTO pull_task_group_execution
                  (id, tenant_id, task_id, seq, source_file_index, source_file_name,
                   execution_status, stage, create_step, group_jid, group_subject,
                   lock_owner, lock_expires_at, version, created_at, updated_at)
                VALUES (11, 7, 100, 1, 1, 'material.txt', 2, 9, 4, '120363000@g.us',
                        '核验群', 'worker', 10000, 2, 100, 100)
                """);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void verificationEvidenceAndStepAdvancePersistTogether() {
        PullTaskGroupExecution before = mapper.selectById(EXECUTION_ID);
        assertThat(before.getProfileVerifiedAt()).isNull();
        assertThat(before.getProfileVerifiedCommandId()).isNull();

        assertThat(mapper.transitionGroupCreate(transition(2).withProfileVerification(COMMAND_ID))).isEqualTo(1);

        PullTaskGroupExecution saved = mapper.selectById(EXECUTION_ID);
        assertThat(saved.getCreateStep()).isEqualTo(PullTaskGroupCreateStep.CAPTURE_INVITE_LINK.code());
        assertThat(saved.getProfileVerifiedAt()).isEqualTo(NOW);
        assertThat(saved.getProfileVerifiedCommandId()).isEqualTo(COMMAND_ID);
        assertThat(saved.getVersion()).isEqualTo(3);
        assertThat(saved.getLockOwner()).isNull();
        assertThat(mapper.selectByTaskId(100L).get(0).getProfileVerifiedCommandId()).isEqualTo(COMMAND_ID);
    }

    @Test
    void staleVersionCannotWriteProofOrAdvanceStep() {
        assertThat(mapper.transitionGroupCreate(transition(1).withProfileVerification(COMMAND_ID))).isZero();
        assertUnverified();
    }

    @Test
    void canceledLeaseCannotWriteProofOrAdvanceStep() {
        jdbc.update("UPDATE pull_task_group_execution SET lock_owner = NULL, lock_expires_at = NULL WHERE id = ?",
                EXECUTION_ID);
        assertThat(mapper.transitionGroupCreate(transition(2).withProfileVerification(COMMAND_ID))).isZero();
        assertUnverified();
    }

    @Test
    void anotherTenantCannotWriteVerificationEvidence() {
        TenantContext.set(8L);
        assertThat(mapper.transitionGroupCreate(transition(2).withProfileVerification(COMMAND_ID))).isZero();
        TenantContext.set(TENANT_ID);
        assertUnverified();
    }

    @Test
    void rollbackRemovesEvidenceAndStepAdvanceTogether() {
        transactions.executeWithoutResult(status -> {
            assertThat(mapper.transitionGroupCreate(transition(2).withProfileVerification(COMMAND_ID))).isEqualTo(1);
            assertThat(mapper.selectById(EXECUTION_ID).getProfileVerifiedCommandId()).isEqualTo(COMMAND_ID);
            status.setRollbackOnly();
        });
        assertUnverified();
    }

    @Test
    void unrelatedCheckpointDoesNotEraseExistingVerificationEvidence() {
        jdbc.update("UPDATE pull_task_group_execution SET profile_verified_at = ?, profile_verified_command_id = ? WHERE id = ?",
                500L, "earlier-profile-command", EXECUTION_ID);

        assertThat(mapper.transitionGroupCreate(transition(2))).isEqualTo(1);

        PullTaskGroupExecution saved = mapper.selectById(EXECUTION_ID);
        assertThat(saved.getProfileVerifiedAt()).isEqualTo(500L);
        assertThat(saved.getProfileVerifiedCommandId()).isEqualTo("earlier-profile-command");
    }

    @Test
    void migrationAddsNullableGuardedColumnsWithoutClaimingHistoricalVerification() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V209__pull_task_group_profile_verification.sql"));
        String statements = sql.lines().filter(line -> !line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(statements).contains("information_schema.columns", "table_schema = DATABASE()",
                "table_name = 'pull_task_group_execution'", "column_name = 'profile_verified_at'",
                "column_name = 'profile_verified_command_id'", "ADD COLUMN profile_verified_at BIGINT NULL DEFAULT NULL",
                "ADD COLUMN profile_verified_command_id VARCHAR(64)", "CHARACTER SET ascii COLLATE ascii_bin");
        assertThat(statements).doesNotContain("UPDATE pull_task_group_execution", "NOT NULL", "CREATE TABLE");
    }

    private void assertUnverified() {
        PullTaskGroupExecution saved = mapper.selectById(EXECUTION_ID);
        assertThat(saved.getCreateStep()).isEqualTo(PullTaskGroupCreateStep.APPLY_PROFILE.code());
        assertThat(saved.getProfileVerifiedAt()).isNull();
        assertThat(saved.getProfileVerifiedCommandId()).isNull();
        assertThat(saved.getVersion()).isEqualTo(2);
    }

    private static PullTaskGroupCreateTransition transition(int version) {
        return new PullTaskGroupCreateTransition(EXECUTION_ID, version, "worker",
                PullTaskExecutionStatus.EXECUTING.code(), PullTaskExecutionStage.GROUP_CREATE.code(),
                PullTaskGroupCreateStep.APPLY_PROFILE.code(), PullTaskExecutionStatus.EXECUTING.code(),
                PullTaskExecutionStage.GROUP_CREATE.code(), PullTaskGroupCreateStep.CAPTURE_INVITE_LINK.code(),
                null, null, null, null, null, null, null, null, null, null, NOW + 1_000L, NOW, null);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {
        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_profile_verification_test");
        }

        @Bean JdbcTemplate jdbcTemplate(DataSource source) {
            return new JdbcTemplate(source);
        }

        @Bean PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }

        @Bean SqlSessionFactory sqlSessionFactory(DataSource source, MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(source, interceptor,
                    "mapper/task/PullTaskGroupExecutionMapper.xml");
        }

        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate session) {
            return session.getMapper(PullTaskGroupExecutionMapper.class);
        }
    }
}
