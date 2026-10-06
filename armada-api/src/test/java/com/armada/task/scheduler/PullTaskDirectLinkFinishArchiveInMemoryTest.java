package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.armada.account.service.AccountService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.group.service.GroupFolderService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实收口 SQL 与事务保证转组失败时执行状态、拉手占用一起回滚。 */
class PullTaskDirectLinkFinishArchiveInMemoryTest {

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private PullTaskGroupExecutionMapper executions;
    private AccountService accounts;
    private PullTaskParentCompletionService completion;
    private PullTaskClosingTransactionService closing;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = PullTaskNormalLinkH2Support.dataSource("direct_archive_" + UUID.randomUUID());
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        var config = new MyBatisConfig();
        var factory = PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource,
                config.mybatisPlusInterceptor(config.tenantLineHandler()),
                "mapper/task/PullTaskMapper.xml",
                "mapper/task/PullTaskGroupExecutionMapper.xml",
                "mapper/task/PullTaskGroupAccountMapper.xml",
                "mapper/task/PullTaskStandardSettingMapper.xml");
        var sessions = new SqlSessionTemplate(factory);
        executions = sessions.getMapper(PullTaskGroupExecutionMapper.class);
        var roles = sessions.getMapper(PullTaskGroupAccountMapper.class);
        accounts = mock(AccountService.class);
        completion = mock(PullTaskParentCompletionService.class);
        closing = new PullTaskClosingTransactionService(
                sessions.getMapper(PullTaskMapper.class), executions, roles,
                sessions.getMapper(PullTaskStandardSettingMapper.class),
                new PullTaskClosingResources(completion, mock(GroupFolderService.class),
                        new PullTaskDirectLinkFinishArchiveService(roles, accounts)));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TenantContext.set(7L);
        jdbc.update("""
                INSERT INTO pull_task(id,tenant_id,task_name,mode,creation_mode,status,config_json,created_at,updated_at)
                VALUES (100,7,'新模式','NORMAL_LINK','DIRECT_LINK','EXECUTING','{}',1,1)
                """);
        jdbc.update("""
                INSERT INTO pull_task_standard_setting(
                    tenant_id,task_id,material_admin_timing,pull_count_min,pull_count_max,
                    pull_interval_seconds,puller_count_per_group,station_count_per_call,concurrent_group_count,
                    puller_group_id,puller_group_name,puller_finish_group_id,created_at,updated_at)
                VALUES (7,100,2,1,1,0,2,0,1,12,'拉手',18,1,1)
                """);
        jdbc.update("""
                INSERT INTO pull_task_group_execution(
                    id,tenant_id,task_id,seq,source_file_index,source_file_name,
                    execution_status,stage,lock_owner,version,lock_expires_at,created_at,updated_at)
                VALUES (11,7,100,1,1,'members.txt',?,?,?,?,2000,1,1)
                """, PullTaskExecutionStatus.EXECUTING.code(), PullTaskExecutionStage.CLOSING.code(), "worker", 1);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void movesOnlyJoinedPullersStillOwnedByThisExecutionInAccountOrder() {
        role(21L, 301L, 2, 1, 5L, null);
        role(22L, 201L, 2, 2, 5L, null);
        role(23L, 401L, 2, 3, 5L, 10L);
        role(24L, 501L, 2, 4, null, null);
        role(25L, 601L, 1, 1, 5L, null);
        role(26L, 701L, 3, 1, 5L, null);

        assertThat(close()).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        verify(accounts).migrateGroup(List.of(201L, 301L), 18L);
        assertThat(executions.selectById(11L).getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.COMPLETED.code());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pull_task_group_account WHERE role_type=2 AND released_at IS NULL
                """, Integer.class)).isZero();
        verify(completion).completeIfTerminalByExecutionId(11L, 1_000L);
    }

    @Test
    void simplifiedNewGroupArchivesOnlyJoinedOwnedManagersAndPullers() {
        jdbc.update("UPDATE pull_task SET creation_mode='SIMPLE_NEW_GROUP' WHERE id=100");
        jdbc.update("UPDATE pull_task_standard_setting SET manager_finish_group_id=19 WHERE task_id=100");
        role(21L, 301L, 2, 1, 5L, null);
        role(22L, 601L, 1, 1, 5L, null);
        role(23L, 602L, 1, 2, null, null);
        role(24L, 603L, 1, 3, 5L, 10L);
        role(25L, 701L, 3, 1, 5L, null);

        assertThat(close()).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        verify(accounts).migrateGroup(List.of(301L), 18L);
        verify(accounts).migrateGroup(List.of(601L), 19L);
        org.mockito.Mockito.verifyNoMoreInteractions(accounts);
        assertThat(executions.selectById(11L).getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.COMPLETED.code());
    }

    @Test
    void simplifiedManagerArchiveConflictRollsBackCompletionAndPullerRelease() {
        jdbc.update("UPDATE pull_task SET creation_mode='SIMPLE_NEW_GROUP' WHERE id=100");
        jdbc.update("UPDATE pull_task_standard_setting SET manager_finish_group_id=19 WHERE task_id=100");
        role(21L, 301L, 2, 1, 5L, null);
        role(22L, 601L, 1, 1, 5L, null);
        doThrow(new BusinessException(ErrorCode.CONFLICT, "管理完成分组冲突"))
                .when(accounts).migrateGroup(List.of(601L), 19L);

        assertThatThrownBy(this::close).isInstanceOf(BusinessException.class);

        assertThat(executions.selectById(11L).getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
        assertThat(jdbc.queryForObject("SELECT released_at FROM pull_task_group_account WHERE id=21", Long.class))
                .isNull();
        verifyNoInteractions(completion);
    }

    @Test
    void archiveConflictRollsBackCompletionAndKeepsPullerLeaseForRetry() {
        role(21L, 301L, 2, 1, 5L, null);
        doThrow(new BusinessException(ErrorCode.CONFLICT, "目标分组被占用"))
                .when(accounts).migrateGroup(List.of(301L), 18L);

        assertThatThrownBy(this::close).isInstanceOf(BusinessException.class);

        PullTaskGroupExecution row = executions.selectById(11L);
        assertThat(row.getExecutionStatus()).isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
        assertThat(row.getVersion()).isEqualTo(1);
        assertThat(row.getFinishedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT released_at FROM pull_task_group_account WHERE id=21", Long.class))
                .isNull();
        verifyNoInteractions(completion);
    }

    @Test
    void legacyModeDoesNotMoveAccountsEvenWithFinishGroupConfigured() {
        jdbc.update("UPDATE pull_task SET creation_mode='PASTED_LINK' WHERE id=100");
        role(21L, 301L, 2, 1, 5L, null);

        assertThat(close()).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        verifyNoInteractions(accounts);
    }

    @Test
    void missingFinishGroupDoesNotCallMigration() {
        jdbc.update("UPDATE pull_task_standard_setting SET puller_finish_group_id=NULL WHERE task_id=100");
        role(21L, 301L, 2, 1, 5L, null);

        assertThat(close()).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        verifyNoInteractions(accounts);
    }

    @Test
    void noOwnedJoinedPullerDoesNotCallMigrationWithEmptyList() {
        role(21L, 301L, 2, 1, 5L, 10L);
        role(22L, 201L, 2, 2, null, null);

        assertThat(close()).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        verifyNoInteractions(accounts);
    }

    private PullTaskExecutionDispatchResult close() {
        return transaction.execute(status -> closing.close(executions.selectById(11L), "worker", 1_000L));
    }

    private void role(long id, long accountId, int roleType, int seq, Long joinedAt, Long releasedAt) {
        jdbc.update("""
                INSERT INTO pull_task_group_account(
                    id,tenant_id,task_id,group_execution_id,account_id,account_phone,role_type,role_seq,
                    joined_at,released_at,created_at,updated_at)
                VALUES (?,7,100,11,?,'919876543210',?,?,?,?,1,1)
                """, id, accountId, roleType, seq, joinedAt, releasedAt);
    }
}
