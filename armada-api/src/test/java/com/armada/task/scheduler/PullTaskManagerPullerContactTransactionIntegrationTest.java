package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskExecutionClaimCriteria;
import com.armada.task.model.dto.PullTaskExecutionClaimState;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.List;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** EX-03 使用真实 Mapper XML 验证拉手选择、双向联系人结果和检查点推进。 */
@SpringJUnitConfig(PullTaskManagerPullerContactTransactionIntegrationTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskManagerPullerContactTransactionIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskGroupAccountMapper groupAccountMapper;
    @Autowired private PullTaskAccountActionMapper actionMapper;
    @Autowired private AccountProtocolLookupService accountLookup;
    @Autowired private ProtocolCommandOutboxService outboxService;
    @Autowired private PullTaskManagerPullerContactTransactionService service;

    @BeforeEach
    void setUp() throws SQLException {
        reset(accountLookup, outboxService);
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        execute("INSERT INTO pull_task "
                + "(id, tenant_id, task_type, task_name, mode, status, config_json, "
                + "created_at, updated_at) VALUES "
                + "(100, 7, 'STANDARD', 'task', 'NORMAL_LINK', 'EXECUTING', '{}', 100, 100)");
        execute("INSERT INTO pull_task_standard_setting "
                + "(tenant_id, task_id, auto_start, material_admin_timing, pull_count_min, "
                + "pull_count_max, pull_interval_seconds, puller_count_per_group, "
                + "station_count_per_call, concurrent_group_count, puller_risk_minutes, "
                + "required_manager_count, manager_group_id, puller_group_id, station_group_id, "
                + "manager_group_name, puller_group_name, station_group_name, created_at, updated_at) "
                + "VALUES (7, 100, 1, 1, 1, 2, 1, 1, 1, 1, 0, 1, 88, 89, 90, "
                + "'manager', 'puller', 'station', 100, 100)");
        PullTaskGroupExecution execution = draft();
        executionMapper.insertDraft(execution);
        executionMapper.freezeDraftRows(100L, 500L);
        execute("UPDATE pull_task_group_execution "
                + "SET execution_status=2, stage=3, version=4, group_jid='120363group@g.us' "
                + "WHERE id=" + execution.getId());
        PullTaskGroupAccount manager = manager(execution.getId());
        groupAccountMapper.insert(manager);
        groupAccountMapper.updateMembership(manager.getId(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 550L, 550L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void firstContactDirectionIsSubmittedToOutboxAndReleasesLease() {
        seedProtocolAccounts();
        when(outboxService.enqueuePullTaskContactSaveCommands(anyList()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(
                        "pull-task:100", List.of("cmd-contact-1"), 1));
        PullTaskGroupExecution firstCandidate = claim("worker-1", 600L, 900L);
        PullTaskExecutionDispatchResult prepared =
                service.prepare(firstCandidate, "worker-1", 610L);

        assertThat(prepared).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        TenantContext.set(7L);
        assertThat(groupAccountMapper.selectByExecutionAndRole(
                firstCandidate.getId(), PullTaskGroupAccountRole.PULLER.code()))
                .singleElement()
                .extracting(PullTaskGroupAccount::getAccountId)
                .isEqualTo(902L);
        assertThat(actionMapper.selectByExecutionAndType(
                firstCandidate.getId(), PullTaskAccountActionType.SAVE_CONTACT.code()))
                .satisfiesExactly(
                        action -> {
                            assertThat(action.getActionStatus())
                                    .isEqualTo(PullTaskActionStatus.SUBMITTED.code());
                            assertThat(action.getCommandId()).isEqualTo("cmd-contact-1");
                        },
                        action -> assertThat(action.getActionStatus())
                                .isEqualTo(PullTaskActionStatus.PENDING.code()));
        PullTaskGroupExecution saved = executionMapper.selectByTaskId(100L).get(0);
        assertThat(saved.getStage()).isEqualTo(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
        assertThat(saved.getExecutionStatus()).isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
        assertThat(saved.getGroupJid()).isEqualTo("120363group@g.us");
        assertThat(saved.getNextRunAt()).isEqualTo(60_610L);
        assertThat(saved.getLockOwner()).isNull();
    }

    @Test
    void submittedContactIsNotRepublishedWhenNextDirectionIsSubmitted() throws SQLException {
        seedProtocolAccounts();
        when(outboxService.enqueuePullTaskContactSaveCommands(anyList()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(
                        "pull-task:100", List.of("cmd-contact-1"), 1))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(
                        "pull-task:100", List.of("cmd-contact-2"), 1));
        PullTaskGroupExecution firstCandidate = claim("worker-1", 600L, 650L);
        service.prepare(firstCandidate, "worker-1", 610L);
        execute("UPDATE pull_task_group_execution SET next_run_at=0 WHERE id="
                + firstCandidate.getId());

        PullTaskGroupExecution recoveredCandidate = claim("worker-2", 700L, 1_000L);
        service.prepare(recoveredCandidate, "worker-2", 710L);

        TenantContext.set(7L);
        assertThat(actionMapper.selectByExecutionAndType(
                firstCandidate.getId(), PullTaskAccountActionType.SAVE_CONTACT.code()))
                .extracting(PullTaskAccountAction::getActionStatus,
                        PullTaskAccountAction::getCommandId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                PullTaskActionStatus.SUBMITTED.code(), "cmd-contact-1"),
                        org.assertj.core.groups.Tuple.tuple(
                                PullTaskActionStatus.SUBMITTED.code(), "cmd-contact-2"));
    }

    @Test
    void noAvailablePullerWaitsOnlyThisExecutionRow() {
        when(accountLookup.findOnlineNormalByGroupId(89L)).thenReturn(List.of());
        PullTaskGroupExecution candidate = claim("worker-1", 600L, 900L);

        PullTaskExecutionDispatchResult result =
                service.prepare(candidate, "worker-1", 610L);

        assertThat(result).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        TenantContext.set(7L);
        PullTaskGroupExecution saved = executionMapper.selectByTaskId(100L).get(0);
        assertThat(saved.getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(saved.getWaitResourceType())
                .isEqualTo(PullTaskWaitResourceType.PULLER.code());
        assertThat(saved.getGroupJid()).isEqualTo("120363group@g.us");
        assertThat(saved.getLockOwner()).isNull();
    }

    @Test
    void pullerOccupiedByAnotherTaskMakesThisExecutionWait() {
        seedProtocolAccounts();
        PullTaskGroupAccount occupied = puller(200L, 502L, 902L);
        groupAccountMapper.insert(occupied);
        PullTaskGroupExecution candidate = claim("worker-1", 600L, 900L);

        PullTaskExecutionDispatchResult result =
                service.prepare(candidate, "worker-1", 610L);

        assertThat(result).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        TenantContext.set(7L);
        PullTaskGroupExecution saved = executionMapper.selectByTaskId(100L).get(0);
        assertThat(saved.getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(saved.getWaitResourceType())
                .isEqualTo(PullTaskWaitResourceType.PULLER.code());
        assertThat(groupAccountMapper.selectByExecutionAndRole(
                502L, PullTaskGroupAccountRole.PULLER.code()))
                .singleElement()
                .extracting(PullTaskGroupAccount::getAccountId)
                .isEqualTo(902L);
    }

    @Test
    void expiredCooldownIsRestoredOnlyAfterOnlineNormalValidation() {
        seedProtocolAccounts();
        PullTaskGroupAccount cooled = puller(200L, 502L, 902L);
        groupAccountMapper.insert(cooled);
        groupAccountMapper.markUnavailable(cooled.getId(),
                PullTaskGroupAccountAvailability.RISK_COOLDOWN.code(),
                "RATE_LIMITED", 650L, 500L);
        groupAccountMapper.releasePuller(cooled.getId(), 510L);
        PullTaskGroupExecution candidate = claim("worker-1", 700L, 1_000L);

        PullTaskExecutionDispatchResult result =
                service.prepare(candidate, "worker-1", 710L);

        assertThat(result).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        TenantContext.set(7L);
        assertThat(groupAccountMapper.selectByExecutionAndRole(
                502L, PullTaskGroupAccountRole.PULLER.code()).get(0)
                .getAvailabilityStatus())
                .isEqualTo(PullTaskGroupAccountAvailability.AVAILABLE.code());
        assertThat(groupAccountMapper.selectByExecutionAndRole(
                candidate.getId(), PullTaskGroupAccountRole.PULLER.code()))
                .singleElement()
                .extracting(PullTaskGroupAccount::getAccountId)
                .isEqualTo(902L);
    }

    private void seedProtocolAccounts() {
        ProtocolAccountRef manager = new ProtocolAccountRef(
                901L, ProtocolBackend.WEB, "manager-901", "8613800000901");
        ProtocolAccountRef puller = new ProtocolAccountRef(
                902L, ProtocolBackend.WEB, "puller-902", "8613800000902");
        when(accountLookup.findOnlineNormalByGroupId(89L)).thenReturn(List.of(puller));
        when(accountLookup.findActiveProtocolRefs(anyList())).thenReturn(List.of(manager, puller));
        when(outboxService.enqueuePullTaskContactSaveCommands(anyList()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(
                        "pull-task:100", List.of("cmd-contact-default"), 1));
    }

    private PullTaskGroupExecution claim(String owner, long now, long expiresAt) {
        TenantContext.clear();
        executionMapper.claimDue(new PullTaskExecutionClaimCriteria(
                new PullTaskExecutionClaimCriteria.Lease(1, now, owner, expiresAt),
                List.of(new PullTaskExecutionClaimState(
                        PullTaskExecutionStatus.EXECUTING.code(),
                        List.of(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code()))),
                new PullTaskExecutionClaimCriteria.Parent(
                        PullTaskType.STANDARD.name(), "NORMAL_LINK",
                        PullTaskStandardStatus.EXECUTING.name())));
        return executionMapper.selectClaimed(owner, now).get(0);
    }

    private static PullTaskGroupExecution draft() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setTaskId(100L);
        row.setSeq(1);
        row.setGroupLinkId(9_001L);
        row.setNormalizedLink("chat.whatsapp.com/AAAA");
        row.setInviteCode("AAAA");
        row.setSourceLinkLineNo(1);
        row.setSourceFileIndex(1);
        row.setSourceFileName("material.txt");
        row.setTotalLineCount(1);
        row.setValidMemberCount(1);
        row.setInvalidLineCount(0);
        row.setDuplicateLineCount(0);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    private static PullTaskGroupAccount manager(long executionId) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(100L);
        row.setGroupExecutionId(executionId);
        row.setAccountId(901L);
        row.setAccountPhone("8613800000901");
        row.setRoleType(PullTaskGroupAccountRole.MANAGER.code());
        row.setRoleSeq(1);
        row.setSourceType(1);
        row.setSelectionMode(1);
        row.setEntryMode(1);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    private static PullTaskGroupAccount puller(
            long taskId, long executionId, long accountId) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(taskId);
        row.setGroupExecutionId(executionId);
        row.setAccountId(accountId);
        row.setAccountPhone("8613800000902");
        row.setRoleType(PullTaskGroupAccountRole.PULLER.code());
        row.setRoleSeq(1);
        row.setSourceType(1);
        row.setSelectionMode(1);
        row.setEntryMode(2);
        row.setOccupiedAt(500L);
        row.setCreatedAt(500L);
        row.setUpdatedAt(500L);
        return row;
    }

    private void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_manager_puller_contact_test");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskMapper.xml",
                    "mapper/task/PullTaskStandardSettingMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskGroupAccountMapper.xml",
                    "mapper/task/PullTaskAccountActionMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        PullTaskMapper taskMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMapper.class);
        }

        @Bean
        PullTaskStandardSettingMapper settingMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskStandardSettingMapper.class);
        }

        @Bean
        PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }

        @Bean
        PullTaskGroupAccountMapper groupAccountMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupAccountMapper.class);
        }

        @Bean
        PullTaskAccountActionMapper actionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskAccountActionMapper.class);
        }

        @Bean
        AccountProtocolLookupService accountLookup() {
            return mock(AccountProtocolLookupService.class);
        }

        @Bean
        PullTaskManagerPullerContactResources resources(
                PullTaskGroupExecutionMapper executionMapper,
                AccountProtocolLookupService accountLookup,
                ProtocolCommandOutboxService outboxService,
                PullTaskExecutionDispatchProperties properties) {
            return new PullTaskManagerPullerContactResources(
                    executionMapper, accountLookup, outboxService, properties);
        }

        @Bean ProtocolCommandOutboxService outboxService() {
            return mock(ProtocolCommandOutboxService.class);
        }

        @Bean PullTaskExecutionDispatchProperties properties() {
            return new PullTaskExecutionDispatchProperties();
        }

        @Bean
        PullTaskManagerPullerContactTransactionService transactionService(
                PullTaskMapper taskMapper,
                PullTaskStandardSettingMapper settingMapper,
                PullTaskGroupAccountMapper groupAccountMapper,
                PullTaskAccountActionMapper actionMapper,
                PullTaskManagerPullerContactResources resources) {
            return new PullTaskManagerPullerContactTransactionService(
                    taskMapper, settingMapper, groupAccountMapper, actionMapper, resources);
        }
    }
}
