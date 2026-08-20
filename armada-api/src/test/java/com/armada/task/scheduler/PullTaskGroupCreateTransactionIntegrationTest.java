package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupCreateParticipantResult;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.platform.protocol.port.GroupCreatePort;
import com.armada.platform.protocol.port.GroupInvitePort;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupCreateStep;
import com.armada.task.service.impl.PullTaskGroupProfileDispatcher;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.List;
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

/** 新群建群七步的真实 Mapper 检查点与角色事实测试。 */
@SpringJUnitConfig(PullTaskGroupCreateTransactionIntegrationTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskGroupCreateTransactionIntegrationTest {

    private static final long NOW = 1_000L;

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskGroupAccountMapper accountMapper;
    @Autowired private PullTaskGroupCreateTransactionService transactions;
    @Autowired private AccountProtocolLookupService accountLookup;
    @Autowired private GroupLinkRegistryService groupRegistry;
    @Autowired private PullTaskGroupProfileDispatcher profileDispatcher;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(
                dataSource, task(), standardSetting(), disabledGroupSetting(), execution());
        reset(accountLookup, groupRegistry, profileDispatcher);
        when(accountLookup.findOnlineNormalStrictByGroupId(16L))
                .thenReturn(List.of(account(901L)));
        when(accountLookup.findOnlineNormalStrictByGroupId(11L))
                .thenReturn(List.of(account(902L)));
        when(accountLookup.findOnlineNormalStrictByGroupId(13L))
                .thenReturn(List.of(account(903L), account(904L)));
        when(accountLookup.findActiveProtocolRef(901L))
                .thenReturn(Optional.of(account(901L)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void persistsRolesResultsInviteAndRegistrationBeforeManagerJoin() {
        PullTaskGroupExecution selectCandidate = executionMapper.selectById(11L);

        assertThat(transactions.prepareRoles(selectCandidate, NOW, 2_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(intColumn("create_step", "pull_task_group_execution", 11L))
                .isEqualTo(PullTaskGroupCreateStep.CREATE_GROUP.code());
        assertThat(stringColumn("create_operation_id", "pull_task_group_execution", 11L))
                .isEqualTo("ptgc:7:11");
        assertThat(stringColumn("group_subject", "pull_task_group_execution", 11L))
                .isEqualTo("印度料子包");
        assertThat(roleAccounts(PullTaskGroupAccountRole.PROMOTER)).containsExactly(901L);
        assertThat(roleAccounts(PullTaskGroupAccountRole.MANAGER)).containsExactly(902L);
        assertThat(roleAccounts(PullTaskGroupAccountRole.STATION)).containsExactly(903L, 904L);

        PullTaskGroupExecution createCandidate = reclaim(NOW + 1);
        var prepared = transactions.prepareCreate(createCandidate, NOW + 1, 2_000L);
        assertThat(prepared.ready()).isTrue();
        assertThat(prepared.command().account().armadaAccountId()).isEqualTo(901L);
        assertThat(prepared.command().participants()).containsExactly(
                "8613800000902", "8613800000903", "8613800000904");
        assertThat(prepared.command().operationId()).isEqualTo("ptgc:7:11");

        GroupCreateResult created = new GroupCreateResult(
                "120363group@g.us", true, List.of(
                new GroupCreateParticipantResult(
                        "8613800000902@s.whatsapp.net", "OK", "200"),
                new GroupCreateParticipantResult(
                        "8613800000903@s.whatsapp.net", "SUCCESS", null),
                new GroupCreateParticipantResult(
                        "8613800000904@s.whatsapp.net", "PRIVACY_BLOCKED", "403")));
        assertThat(transactions.completeCreate(
                createCandidate, created, NOW + 4_000L, NOW + 1))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(membership(PullTaskGroupAccountRole.PROMOTER, 901L))
                .isEqualTo(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        assertThat(membership(PullTaskGroupAccountRole.MANAGER, 902L))
                .isEqualTo(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        assertThat(membership(PullTaskGroupAccountRole.STATION, 903L))
                .isEqualTo(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        assertThat(membership(PullTaskGroupAccountRole.STATION, 904L))
                .isEqualTo(PullTaskGroupAccountMembershipStatus.NOT_JOINED.code());

        PullTaskGroupExecution profileCandidate = reclaim(NOW + 2);
        assertThat(transactions.applyProfile(
                profileCandidate, PullTaskGroupCreateStep.CAPTURE_INVITE_LINK,
                NOW + 5_000L, NOW + 2))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        verify(profileDispatcher).dispatchIfDue(
                eq(profileCandidate), eq(com.armada.task.model.enums
                        .PullTaskGroupSettingTiming.BEFORE_PULL), eq(NOW + 2));

        PullTaskGroupExecution inviteCandidate = reclaim(NOW + 3);
        assertThat(transactions.prepareInvite(inviteCandidate, 2_000L, NOW + 3).ready())
                .isTrue();
        assertThat(transactions.completeInvite(
                inviteCandidate,
                new GroupInviteResult(
                        "120363group@g.us", "Invite123", null),
                NOW + 6_000L,
                NOW + 3))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(stringColumn("normalized_link", "pull_task_group_execution", 11L))
                .isEqualTo("chat.whatsapp.com/Invite123");

        PullTaskGroupExecution settingsCandidate = reclaim(NOW + 4);
        assertThat(transactions.applyProfile(
                settingsCandidate, PullTaskGroupCreateStep.REGISTER_GROUP,
                NOW + 7_000L, NOW + 4))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        when(groupRegistry.registerSelfBuiltGroup(
                eq("120363group@g.us"), eq("印度料子包"), eq(901L),
                eq("8613800000901"), eq(3), anyLong())).thenReturn(21L);
        PullTaskGroupExecution registerCandidate = reclaim(NOW + 5);
        assertThat(transactions.registerGroup(registerCandidate, NOW + 5))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(intColumn("stage", "pull_task_group_execution", 11L))
                .isEqualTo(PullTaskExecutionStage.MANAGER_JOIN.code());
        assertThat(longColumn("group_link_id", "pull_task_group_execution", 11L))
                .isEqualTo(21L);
        verify(groupRegistry).registerKnownMembership(
                21L, "120363group@g.us", 902L, false, NOW + 5);
        verify(groupRegistry).registerKnownMembership(
                21L, "120363group@g.us", 903L, false, NOW + 5);
    }

    @Test
    void unconfirmedCreatePausesWithoutChangingOperationIdOrAttemptCount() {
        assertThat(transactions.prepareRoles(executionMapper.selectById(11L), NOW, 2_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        PullTaskGroupExecution createCandidate = reclaim(NOW + 1);

        assertThat(transactions.failCreate(
                createCandidate,
                new ProtocolException(
                        ProtocolErrorCode.GROUP_CREATE_RESULT_UNCONFIRMED,
                        "timeout after submit"),
                2_000L,
                NOW + 1))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(intColumn("manual_paused", "pull_task_group_execution", 11L)).isOne();
        assertThat(intColumn("create_attempt_count", "pull_task_group_execution", 11L))
                .isZero();
        assertThat(stringColumn("create_operation_id", "pull_task_group_execution", 11L))
                .isEqualTo("ptgc:7:11");
        assertThat(stringColumn("reason_code", "pull_task_group_execution", 11L))
                .isEqualTo("GROUP_CREATE_RESULT_UNCONFIRMED");
    }

    private PullTaskGroupExecution reclaim(long now) {
        jdbc.update("UPDATE pull_task_group_execution SET lock_owner = 'worker', "
                + "lock_expires_at = ? WHERE id = 11", now + 10_000L);
        return executionMapper.selectById(11L);
    }

    private List<Long> roleAccounts(PullTaskGroupAccountRole role) {
        return accountMapper.selectByExecutionAndRole(11L, role.code()).stream()
                .map(row -> row.getAccountId())
                .toList();
    }

    private int membership(PullTaskGroupAccountRole role, long accountId) {
        return accountMapper.selectByExecutionAndRole(11L, role.code()).stream()
                .filter(row -> row.getAccountId() == accountId)
                .findFirst().orElseThrow().getMembershipStatus();
    }

    private int intColumn(String column, String table, long id) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id = ?",
                Integer.class, id);
    }

    private long longColumn(String column, String table, long id) {
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
                + "(id, tenant_id, task_type, task_name, mode, creation_mode, status, "
                + "version, config_json, created_at, updated_at) VALUES "
                + "(1, 7, 'STANDARD', 'task', 'NORMAL_LINK', 'NEW_GROUP', "
                + "'EXECUTING', 1, '{}', 100, 100)";
    }

    private static String standardSetting() {
        return "INSERT INTO pull_task_standard_setting "
                + "(tenant_id, task_id, material_admin_timing, puller_sync_mode, "
                + "pull_count_min, pull_count_max, pull_interval_seconds, "
                + "puller_count_per_group, station_count_per_call, initial_station_count, "
                + "concurrent_group_count, manager_group_id, puller_group_id, "
                + "station_group_id, creator_group_id, manager_group_name, puller_group_name, "
                + "station_group_name, creator_group_name, created_at, updated_at) VALUES "
                + "(7, 1, 1, 1, 5, 10, 30, 1, 1, 2, 1, 11, 12, 13, 16, "
                + "'管理', '拉手', '站台', '建群人', 100, 100)";
    }

    private static String disabledGroupSetting() {
        return "INSERT INTO pull_task_standard_group_setting "
                + "(tenant_id, task_id, is_group_setting_enabled, setting_timing, group_name, "
                + "is_material_filename_as_group_name, edit_permission_mode, mute_mode, "
                + "link_permission_mode, disappearing_message_mode, created_at, updated_at) "
                + "VALUES (7, 1, 0, 1, '配置群名', 0, 0, 0, 2, 0, 100, 100)";
    }

    private static String execution() {
        return "INSERT INTO pull_task_group_execution "
                + "(id, tenant_id, task_id, seq, source_file_index, source_file_name, "
                + "execution_status, stage, create_step, create_attempt_count, manual_paused, "
                + "next_run_at, lock_owner, lock_expires_at, version, created_at, updated_at) "
                + "VALUES (11, 7, 1, 1, 1, '印度料子包.txt', 2, 9, 1, 0, 0, 0, "
                + "'worker', 10000, 2, 100, 100)";
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_group_create_tx_test");
        }

        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskGroupAccountMapper.xml",
                    "mapper/task/PullTaskStandardSettingMapper.xml",
                    "mapper/task/PullTaskStandardGroupSettingMapper.xml");
        }

        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }

        @Bean PullTaskGroupAccountMapper accountMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupAccountMapper.class);
        }

        @Bean PullTaskStandardSettingMapper settingMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskStandardSettingMapper.class);
        }

        @Bean PullTaskStandardGroupSettingMapper groupSettingMapper(
                SqlSessionTemplate template) {
            return template.getMapper(PullTaskStandardGroupSettingMapper.class);
        }

        @Bean AccountProtocolLookupService accountLookup() {
            return mock(AccountProtocolLookupService.class);
        }

        @Bean GroupCreatePort groupCreatePort() {
            return mock(GroupCreatePort.class);
        }

        @Bean GroupInvitePort groupInvitePort() {
            return mock(GroupInvitePort.class);
        }

        @Bean GroupLinkRegistryService groupRegistry() {
            return mock(GroupLinkRegistryService.class);
        }

        @Bean PullTaskGroupProfileDispatcher profileDispatcher() {
            return mock(PullTaskGroupProfileDispatcher.class);
        }

        @Bean PullTaskGroupCreatePersistence persistence(
                PullTaskStandardSettingMapper settingMapper,
                PullTaskStandardGroupSettingMapper groupSettingMapper,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskGroupAccountMapper accountMapper) {
            return new PullTaskGroupCreatePersistence(
                    settingMapper, groupSettingMapper, executionMapper, accountMapper);
        }

        @Bean PullTaskGroupCreateResources resources(
                AccountProtocolLookupService accountLookup,
                GroupCreatePort groupCreatePort,
                GroupInvitePort groupInvitePort,
                GroupLinkRegistryService groupRegistry,
                PullTaskGroupProfileDispatcher profileDispatcher) {
            return new PullTaskGroupCreateResources(
                    accountLookup, groupCreatePort, groupInvitePort,
                    groupRegistry, profileDispatcher);
        }

        @Bean PullTaskGroupCreateTransactionService transactions(
                PullTaskGroupCreatePersistence persistence,
                PullTaskGroupCreateResources resources) {
            return new PullTaskGroupCreateTransactionService(persistence, resources);
        }
    }
}
