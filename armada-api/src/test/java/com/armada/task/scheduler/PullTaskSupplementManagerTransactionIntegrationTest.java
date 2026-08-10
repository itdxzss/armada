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
import com.armada.task.model.dto.PullTaskSupplementManagerWork;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskSupplementManagerOperation;
import com.armada.task.model.enums.PullTaskWaitResourceType;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** OP-01 补充管理员入群、提权和执行阶段恢复的真实 Mapper 事务测试。 */
@SpringJUnitConfig(PullTaskSupplementManagerTransactionIntegrationTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskSupplementManagerTransactionIntegrationTest {

    private static final long NOW = 1_000L;

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskSupplementManagerTransactionService transactions;
    @Autowired private AccountProtocolLookupService accountLookup;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource,
                task(), execution(), currentManager(), supplementManager(), entryAction());
        reset(accountLookup);
        when(accountLookup.findActiveProtocolRef(901L)).thenReturn(
                java.util.Optional.of(account(901L)));
        when(accountLookup.findActiveProtocolRef(902L)).thenReturn(
                java.util.Optional.of(account(902L)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void persistsEntryThenPromotionCheckpointsBeforeReturningToContactStage() {
        PullTaskGroupExecution candidate = executionMapper.selectById(11L);

        PullTaskSupplementManagerPreparation entry =
                transactions.prepare(candidate, "worker", NOW);

        assertThat(entry.ready()).isTrue();
        PullTaskSupplementManagerWork entryWork = entry.work();
        assertThat(entryWork.operation())
                .isEqualTo(PullTaskSupplementManagerOperation.JOIN_BY_LINK);
        assertThat(entryWork.joinCommand().inviteLinkOrCode())
                .isEqualTo("https://chat.whatsapp.com/AAAA");
        assertThat(intColumn("action_status", "pull_task_account_action", 201L))
                .isEqualTo(PullTaskActionStatus.SUBMITTED.code());
        assertThat(intColumn("membership_status", "pull_task_group_account", 102L))
                .isEqualTo(PullTaskGroupAccountMembershipStatus.JOINING.code());

        assertThat(transactions.complete(
                entryWork, PullTaskSupplementManagerOutcome.entryConfirmed(), NOW))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertThat(intColumn("action_status", "pull_task_account_action", 201L))
                .isEqualTo(PullTaskActionStatus.SUCCESS.code());
        assertThat(intColumn("membership_status", "pull_task_group_account", 102L))
                .isEqualTo(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        assertThat(intColumn("stage", "pull_task_group_execution", 11L))
                .isEqualTo(PullTaskExecutionStage.MANAGER_JOIN.code());

        reclaim();
        PullTaskGroupExecution adminCandidate = executionMapper.selectById(11L);
        PullTaskSupplementManagerPreparation admin =
                transactions.prepare(adminCandidate, "worker", NOW + 1);

        assertThat(admin.ready()).isTrue();
        assertThat(admin.work().operation())
                .isEqualTo(PullTaskSupplementManagerOperation.PROMOTE_ADMIN);
        assertThat(admin.work().actor().armadaAccountId()).isEqualTo(901L);
        assertThat(admin.work().target().armadaAccountId()).isEqualTo(902L);
        assertThat(admin.work().verificationOnly()).isFalse();
        assertThat(intColumn("admin_status", "pull_task_group_account", 102L))
                .isEqualTo(PullTaskGroupAccountAdminStatus.PENDING.code());
        assertThat(transactions.markAdminSubmitted(admin.work(), NOW + 1)).isTrue();
        assertThat(intColumn("admin_status", "pull_task_group_account", 102L))
                .isEqualTo(PullTaskGroupAccountAdminStatus.SUBMITTED.code());

        assertThat(transactions.complete(
                admin.work(), PullTaskSupplementManagerOutcome.adminConfirmed(), NOW + 1))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(intColumn("admin_status", "pull_task_group_account", 102L))
                .isEqualTo(PullTaskGroupAccountAdminStatus.SUCCESS.code());
        assertThat(intColumn("execution_status", "pull_task_group_execution", 11L))
                .isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
        assertThat(intColumn("stage", "pull_task_group_execution", 11L))
                .isEqualTo(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
    }

    @Test
    void androidLinkJoinUsesTheFrozenPureInviteCode() {
        when(accountLookup.findActiveProtocolRef(902L)).thenReturn(
                java.util.Optional.of(account(902L, ProtocolBackend.ANDROID)));

        PullTaskSupplementManagerPreparation entry = transactions.prepare(
                executionMapper.selectById(11L), "worker", NOW);

        assertThat(entry.ready()).isTrue();
        assertThat(entry.work().operation())
                .isEqualTo(PullTaskSupplementManagerOperation.JOIN_BY_LINK);
        assertThat(entry.work().joinCommand().inviteLinkOrCode()).isEqualTo("AAAA");
    }

    @Test
    void frozenManagerInvitationUsesItsStoredActorInsteadOfReselecting() {
        jdbc.update("UPDATE pull_task_group_account SET entry_mode = 2 WHERE id = 102");
        jdbc.update("UPDATE pull_task_account_action "
                + "SET action_type = 2, actor_group_account_id = 101 WHERE id = 201");

        PullTaskSupplementManagerPreparation prepared = transactions.prepare(
                executionMapper.selectById(11L), "worker", NOW);

        assertThat(prepared.ready()).isTrue();
        assertThat(prepared.work().operation())
                .isEqualTo(PullTaskSupplementManagerOperation.MANAGER_INVITE);
        assertThat(prepared.work().actor().armadaAccountId()).isEqualTo(901L);
        assertThat(prepared.work().target().armadaAccountId()).isEqualTo(902L);
    }

    @Test
    void pendingApprovalPersistsAGroupScopedPause() {
        PullTaskSupplementManagerPreparation entry = transactions.prepare(
                executionMapper.selectById(11L), "worker", NOW);

        assertThat(transactions.complete(
                entry.work(), PullTaskSupplementManagerOutcome.entryPendingApproval(), NOW))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertThat(intColumn("action_status", "pull_task_account_action", 201L))
                .isEqualTo(PullTaskActionStatus.PENDING_APPROVAL.code());
        assertThat(intColumn("membership_status", "pull_task_group_account", 102L))
                .isEqualTo(PullTaskGroupAccountMembershipStatus.PENDING_APPROVAL.code());
        assertThat(intColumn("execution_status", "pull_task_group_execution", 11L))
                .isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(intColumn("wait_resource_type", "pull_task_group_execution", 11L))
                .isEqualTo(PullTaskWaitResourceType.APPROVAL.code());
        assertThat(jdbc.queryForObject(
                "SELECT reason_code FROM pull_task_group_execution WHERE id = 11", String.class))
                .isEqualTo("MANAGER_JOIN_PENDING_APPROVAL");
        assertThat(jdbc.queryForObject(
                "SELECT reason_message FROM pull_task_group_execution WHERE id = 11", String.class))
                .isEqualTo("管理员已提交入群申请，等待群主或管理员审批；该群拉群已暂停");
        assertThat(jdbc.queryForObject(
                "SELECT next_run_at FROM pull_task_group_execution WHERE id = 11", Long.class))
                .isZero();
    }

    private void reclaim() {
        jdbc.update("UPDATE pull_task_group_execution "
                + "SET lock_owner = 'worker', lock_expires_at = 5000 WHERE id = 11");
    }

    private int intColumn(String column, String table, long id) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id = ?",
                Integer.class, id);
    }

    private static ProtocolAccountRef account(long id) {
        return account(id, ProtocolBackend.WEB);
    }

    private static ProtocolAccountRef account(long id, ProtocolBackend backend) {
        return new ProtocolAccountRef(
                id, backend, "acc-" + id, "8613800000" + id);
    }

    private static String task() {
        return "INSERT INTO pull_task "
                + "(id, tenant_id, task_type, task_name, mode, status, version, "
                + "config_json, created_at, updated_at) VALUES "
                + "(1, 7, 'STANDARD', 'task', 'NORMAL_LINK', 'EXECUTING', 1, '{}', 100, 100)";
    }

    private static String execution() {
        return "INSERT INTO pull_task_group_execution "
                + "(id, tenant_id, task_id, seq, normalized_link, invite_code, source_link_line_no, "
                + "source_file_index, source_file_name, group_jid, execution_status, stage, "
                + "manual_paused, next_run_at, lock_owner, lock_expires_at, version, "
                + "created_at, updated_at) VALUES "
                + "(11, 7, 1, 1, 'chat.whatsapp.com/AAAA', 'AAAA', 1, 1, 'a.txt', "
                + "'120363group@g.us', 2, 2, 0, 0, 'worker', 5000, 2, 100, 100)";
    }

    private static String currentManager() {
        return manager(101L, 901L, new ManagerState(1, 1, 2, 3, 1));
    }

    private static String supplementManager() {
        return manager(102L, 902L, new ManagerState(2, 2, 0, 1, 1));
    }

    private static String manager(long id, long accountId, ManagerState state) {
        return "INSERT INTO pull_task_group_account "
                + "(id, tenant_id, task_id, group_execution_id, account_id, account_phone, "
                + "role_type, role_seq, source_type, selection_mode, entry_mode, membership_status, "
                + "admin_status, availability_status, created_at, updated_at) VALUES ("
                + id + ", 7, 1, 11, " + accountId + ", '8613800000" + accountId
                + "', 1, " + state.roleSeq() + ", " + state.sourceType()
                + ", 2, 1, " + state.membership() + ", " + state.admin()
                + ", " + state.availability() + ", 100, 100)";
    }

    private static String entryAction() {
        return "INSERT INTO pull_task_account_action "
                + "(id, tenant_id, task_id, group_execution_id, action_type, "
                + "actor_group_account_id, target_group_account_id, action_status, "
                + "created_at, updated_at) VALUES (201, 7, 1, 11, 3, 102, 102, 1, 100, 100)";
    }

    private record ManagerState(
            int roleSeq,
            int sourceType,
            int membership,
            int admin,
            int availability) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource(
                    "pull_task_supplement_manager_tx_test");
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
        PullTaskSupplementManagerTransactionService transactions(
                PullTaskMapper taskMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskAccountActionMapper actionMapper,
                PullTaskGroupExecutionMapper executionMapper,
                AccountProtocolLookupService accountLookup) {
            return new PullTaskSupplementManagerTransactionService(
                    taskMapper, accountMapper, actionMapper,
                    new PullTaskSupplementManagerResources(executionMapper, accountLookup));
        }
    }
}
