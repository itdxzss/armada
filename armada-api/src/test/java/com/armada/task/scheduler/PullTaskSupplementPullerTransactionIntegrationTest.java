package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.model.dto.PullTaskSupplementPullerWork;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.Optional;
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
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** OP-02 补充拉手踩链接、未知结果与资源等待的真实 Mapper 事务测试。 */
@SpringJUnitConfig(PullTaskSupplementPullerTransactionIntegrationTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskSupplementPullerTransactionIntegrationTest {

    private static final long NOW = 1_000L;

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskSupplementPullerTransactionService transactions;
    @Autowired private AccountProtocolLookupService accountLookup;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(
                dataSource, task(), execution(), supplementPuller(), linkAction());
        reset(accountLookup);
        when(accountLookup.findActiveProtocolRef(902L))
                .thenReturn(Optional.of(account(902L)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void confirmedLinkJoinPersistsFactsAndReturnsToContactCheckpoint() {
        PullTaskSupplementPullerPreparation prepared = transactions.prepare(
                executionMapper.selectById(11L), "worker", NOW);

        assertThat(prepared.ready()).isTrue();
        PullTaskSupplementPullerWork work = prepared.work();
        assertThat(work.verificationOnly()).isFalse();
        assertThat(intColumn("action_status", "pull_task_account_action", 201L))
                .isEqualTo(PullTaskActionStatus.SUBMITTED.code());
        assertThat(intColumn("membership_status", "pull_task_group_account", 102L))
                .isEqualTo(PullTaskGroupAccountMembershipStatus.JOINING.code());

        assertThat(transactions.complete(
                work, PullTaskSupplementPullerOutcome.confirmed(), NOW + 1))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertThat(intColumn("action_status", "pull_task_account_action", 201L))
                .isEqualTo(PullTaskActionStatus.SUCCESS.code());
        assertThat(intColumn("membership_status", "pull_task_group_account", 102L))
                .isEqualTo(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        assertThat(intColumn("execution_status", "pull_task_group_execution", 11L))
                .isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
        assertThat(intColumn("stage", "pull_task_group_execution", 11L))
                .isEqualTo(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
        assertThat(stringColumn("lock_owner", "pull_task_group_execution", 11L)).isNull();
    }

    @Test
    void unknownMembershipWaitsForPullerAndReleasesAllPullerLeases() {
        PullTaskSupplementPullerWork work = transactions.prepare(
                executionMapper.selectById(11L), "worker", NOW).work();

        assertThat(transactions.complete(
                work, PullTaskSupplementPullerOutcome.unknown("VERIFY_TIMEOUT"), NOW + 1))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(intColumn("action_status", "pull_task_account_action", 201L))
                .isEqualTo(PullTaskActionStatus.UNKNOWN.code());
        assertThat(intColumn("membership_status", "pull_task_group_account", 102L))
                .isEqualTo(PullTaskGroupAccountMembershipStatus.UNKNOWN.code());
        assertThat(intColumn("execution_status", "pull_task_group_execution", 11L))
                .isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(intColumn("wait_resource_type", "pull_task_group_execution", 11L))
                .isEqualTo(PullTaskWaitResourceType.PULLER.code());
        assertThat(longColumn("released_at", "pull_task_group_account", 102L))
                .isEqualTo(NOW + 1);
    }

    @Test
    void submittedActionWithOfflineAccountWaitsWithoutReplayingTheCommand() {
        jdbc.update("UPDATE pull_task_account_action SET action_status=2, "
                + "command_id='existing-command', submitted_at=900 WHERE id=201");
        jdbc.update("UPDATE pull_task_group_account SET membership_status=1 WHERE id=102");
        when(accountLookup.findActiveProtocolRef(902L)).thenReturn(Optional.empty());

        PullTaskSupplementPullerPreparation prepared = transactions.prepare(
                executionMapper.selectById(11L), "worker", NOW);

        assertThat(prepared.ready()).isFalse();
        assertThat(prepared.result()).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertThat(intColumn("action_status", "pull_task_account_action", 201L))
                .isEqualTo(PullTaskActionStatus.SUBMITTED.code());
        assertThat(intColumn("execution_status", "pull_task_group_execution", 11L))
                .isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(intColumn("wait_resource_type", "pull_task_group_execution", 11L))
                .isEqualTo(PullTaskWaitResourceType.PULLER.code());
        assertThat(longColumn("released_at", "pull_task_group_account", 102L))
                .isEqualTo(NOW);
    }

    private int intColumn(String column, String table, long id) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id = ?",
                Integer.class, id);
    }

    private Long longColumn(String column, String table, long id) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id = ?",
                Long.class, id);
    }

    private String stringColumn(String column, String table, long id) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id = ?",
                String.class, id);
    }

    private static ProtocolAccountRef account(long id) {
        return new ProtocolAccountRef(
                id, ProtocolBackend.WEB, "acc-" + id, "8613800000" + id);
    }

    private static String task() {
        return "INSERT INTO pull_task "
                + "(id, tenant_id, task_type, task_name, mode, status, version, "
                + "config_json, created_at, updated_at) VALUES "
                + "(1, 7, 'STANDARD', 'task', 'NORMAL_LINK', 'EXECUTING', 1, '{}', 100, 100)";
    }

    private static String execution() {
        return "INSERT INTO pull_task_group_execution "
                + "(id, tenant_id, task_id, seq, normalized_link, invite_code, "
                + "source_link_line_no, source_file_index, source_file_name, group_jid, "
                + "execution_status, stage, manual_paused, next_run_at, lock_owner, "
                + "lock_expires_at, version, created_at, updated_at) VALUES "
                + "(11, 7, 1, 1, 'chat.whatsapp.com/AAAA', 'AAAA', 1, 1, 'a.txt', "
                + "'120363group@g.us', 2, 3, 0, 0, 'worker', 5000, 2, 100, 100)";
    }

    private static String supplementPuller() {
        return "INSERT INTO pull_task_group_account "
                + "(id, tenant_id, task_id, group_execution_id, account_id, account_phone, "
                + "role_type, role_seq, source_type, selection_mode, entry_mode, "
                + "membership_status, admin_status, availability_status, occupied_at, "
                + "created_at, updated_at) VALUES "
                + "(102, 7, 1, 11, 902, '8613800000902', 2, 1, 2, 2, 1, "
                + "0, 0, 1, 100, 100, 100)";
    }

    private static String linkAction() {
        return "INSERT INTO pull_task_account_action "
                + "(id, tenant_id, task_id, group_execution_id, action_type, "
                + "actor_group_account_id, target_group_account_id, action_status, "
                + "created_at, updated_at) VALUES "
                + "(201, 7, 1, 11, 3, 102, 102, 1, 100, 100)";
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource(
                    "pull_task_supplement_puller_tx_test");
        }

        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskGroupAccountMapper.xml",
                    "mapper/task/PullTaskAccountActionMapper.xml");
        }

        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean PullTaskMapper taskMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMapper.class);
        }

        @Bean PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }

        @Bean PullTaskGroupAccountMapper accountMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupAccountMapper.class);
        }

        @Bean PullTaskAccountActionMapper actionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskAccountActionMapper.class);
        }

        @Bean AccountProtocolLookupService accountLookup() {
            return mock(AccountProtocolLookupService.class);
        }

        @Bean
        PullTaskSupplementPullerTransactionService transactions(
                PullTaskMapper taskMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskAccountActionMapper actionMapper,
                PullTaskGroupExecutionMapper executionMapper,
                AccountProtocolLookupService accountLookup) {
            return new PullTaskSupplementPullerTransactionService(
                    taskMapper, accountMapper, actionMapper,
                    new PullTaskSupplementPullerResources(executionMapper, accountLookup));
        }
    }
}
