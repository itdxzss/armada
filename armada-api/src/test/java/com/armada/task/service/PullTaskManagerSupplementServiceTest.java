package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.model.entity.AccountGroup;
import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskManagerSupplementDTO;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskAccountEntryMode;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountSource;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskSelectionMode;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import com.armada.task.model.vo.PullTaskManagerSupplementOptionsVO;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import com.armada.task.service.impl.PullTaskManagerSupplementServiceImpl;
import com.armada.task.service.impl.PullTaskManagerSupplementResources;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** OP-01 补充管理员选择、不可变指令和等待行唤醒的真实 Mapper 测试。 */
@SpringJUnitConfig(PullTaskManagerSupplementServiceTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskManagerSupplementServiceTest {

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PullTaskManagerSupplementService service;
    @Autowired private AccountProtocolLookupService accountLookup;
    @Autowired private AccountGroupService accountGroupService;
    @Autowired private PullTaskExecutionDispatchTrigger dispatchTrigger;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource,
                task(), setting(), execution(), unavailableManager());
        reset(accountLookup, accountGroupService, dispatchTrigger);
        when(accountGroupService.requireExisting(88L)).thenReturn(accountGroup(88L));
        when(accountLookup.findOnlineNormalByGroupId(88L)).thenReturn(List.of(
                account(901L, "8613800000901"),
                account(902L, "8613800000902")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void optionsUseTheFrozenOneManagerRequirementAndExcludePreviouslySelectedAccounts() {
        PullTaskManagerSupplementOptionsVO options = service.options(1L, 11L, null);

        assertThat(options.requiredManagerCount()).isEqualTo(1);
        assertThat(options.currentManagerCount()).isZero();
        assertThat(options.missingManagerCount()).isEqualTo(1);
        assertThat(options.managerGroupId()).isEqualTo(88L);
        assertThat(options.managerInviteAvailable()).isFalse();
        assertThat(options.candidates()).extracting(candidate -> candidate.accountId())
                .containsExactly(902L);
        assertThat(options.currentManagers()).extracting(manager -> manager.accountId())
                .containsExactly(901L);
    }

    @Test
    void linkSupplementPersistsImmutableSelectionAndRewindsOnlyTheWaitingExecution() {
        service.supplement(1L, 11L, new PullTaskManagerSupplementDTO(
                88L, 902L, PullTaskAccountEntryMode.JOIN_BY_LINK.code(), null));

        assertThat(jdbc.queryForMap(
                "SELECT * FROM pull_task_group_account WHERE account_id = 902")).satisfies(row -> {
                    assertThat(row.get("ROLE_TYPE"))
                            .isEqualTo(PullTaskGroupAccountRole.MANAGER.code());
                    assertThat(row.get("ROLE_SEQ")).isEqualTo(2);
                    assertThat(row.get("SOURCE_TYPE"))
                            .isEqualTo(PullTaskGroupAccountSource.SUPPLEMENT.code());
                    assertThat(row.get("SELECTION_MODE"))
                            .isEqualTo(PullTaskSelectionMode.MANUAL.code());
                    assertThat(row.get("ENTRY_MODE"))
                            .isEqualTo(PullTaskAccountEntryMode.JOIN_BY_LINK.code());
                });
        assertThat(jdbc.queryForMap(
                "SELECT * FROM pull_task_account_action WHERE target_group_account_id = "
                        + "(SELECT id FROM pull_task_group_account WHERE account_id = 902)"))
                .satisfies(row -> {
                    assertThat(row.get("ACTION_TYPE"))
                            .isEqualTo(PullTaskAccountActionType.JOIN_BY_LINK.code());
                    assertThat(row.get("ACTION_STATUS"))
                            .isEqualTo(PullTaskActionStatus.PENDING.code());
                    assertThat(row.get("ACTOR_GROUP_ACCOUNT_ID"))
                            .isEqualTo(row.get("TARGET_GROUP_ACCOUNT_ID"));
                });
        assertThat(jdbc.queryForMap(
                "SELECT * FROM pull_task_group_execution WHERE id = 11")).satisfies(row -> {
                    assertThat(row.get("EXECUTION_STATUS"))
                            .isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
                    assertThat(row.get("STAGE"))
                            .isEqualTo(PullTaskExecutionStage.MANAGER_JOIN.code());
                    assertThat(row.get("MANUAL_PAUSED")).isEqualTo(0);
                    assertThat(row.get("LOCK_OWNER")).isNull();
                });
        verify(dispatchTrigger).dispatchAfterCommit();
    }

    @Test
    void rejectsInvitationWithoutCurrentExecutorAndNeverOverfillsOneManagerSlot() {
        assertThatThrownBy(() -> service.supplement(1L, 11L,
                new PullTaskManagerSupplementDTO(
                        88L, 902L, PullTaskAccountEntryMode.MANAGER_INVITE.code(), 101L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前管理员");

        service.supplement(1L, 11L, new PullTaskManagerSupplementDTO(
                88L, 902L, PullTaskAccountEntryMode.JOIN_BY_LINK.code(), null));
        when(accountLookup.findOnlineNormalByGroupId(88L)).thenReturn(List.of(
                account(903L, "8613800000903")));

        assertThatThrownBy(() -> service.supplement(1L, 11L,
                new PullTaskManagerSupplementDTO(
                        88L, 903L, PullTaskAccountEntryMode.JOIN_BY_LINK.code(), null)))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pull_task_group_account WHERE source_type = 2",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsCandidateOutsideSelectedOnlineGroupAndNonManagerWaitRows() {
        assertThatThrownBy(() -> service.supplement(1L, 11L,
                new PullTaskManagerSupplementDTO(
                        88L, 999L, PullTaskAccountEntryMode.JOIN_BY_LINK.code(), null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("候选");

        jdbc.update("UPDATE pull_task_group_execution SET wait_resource_type = ? WHERE id = 11",
                PullTaskWaitResourceType.PULLER.code());
        assertThatThrownBy(() -> service.supplement(1L, 11L,
                new PullTaskManagerSupplementDTO(
                        88L, 902L, PullTaskAccountEntryMode.JOIN_BY_LINK.code(), null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("等待管理员");
    }

    private static ProtocolAccountRef account(long id, String phone) {
        return new ProtocolAccountRef(id, ProtocolBackend.WEB, "acc-" + id, phone);
    }

    private static AccountGroup accountGroup(long id) {
        AccountGroup row = new AccountGroup();
        row.setId(id);
        row.setName("管理员组");
        return row;
    }

    private static String task() {
        return "INSERT INTO pull_task "
                + "(id, tenant_id, task_type, task_name, mode, status, version, "
                + "config_json, created_at, updated_at) VALUES "
                + "(1, 7, 'STANDARD', 'task', 'NORMAL_LINK', 'EXECUTING', 1, '{}', 100, 100)";
    }

    private static String setting() {
        return "INSERT INTO pull_task_standard_setting "
                + "(task_id, tenant_id, auto_start, material_admin_timing, pull_interval_seconds, concurrent_group_count, "
                + "puller_count_per_group, pull_count_min, pull_count_max, station_count_per_call, "
                + "puller_risk_minutes, manager_group_id, puller_group_id, station_group_id, "
                + "required_manager_count, manager_group_name, puller_group_name, "
                + "station_group_name, created_at, updated_at) VALUES "
                + "(1, 7, 0, 1, 30, 1, 1, 1, 2, 0, 0, 88, 89, 90, 1, "
                + "'manager', 'puller', 'station', 100, 100)";
    }

    private static String execution() {
        return "INSERT INTO pull_task_group_execution "
                + "(id, tenant_id, task_id, seq, normalized_link, invite_code, source_link_line_no, "
                + "source_file_index, source_file_name, group_jid, execution_status, stage, "
                + "manual_paused, wait_resource_type, reason_code, next_run_at, lock_owner, "
                + "lock_expires_at, version, created_at, updated_at) VALUES "
                + "(11, 7, 1, 1, 'chat.whatsapp.com/AAAA', 'AAAA', 1, 1, 'a.txt', "
                + "'120363group@g.us', 3, 5, 0, 1, 'MANAGER_UNAVAILABLE', 5000, "
                + "NULL, NULL, 2, 100, 100)";
    }

    private static String unavailableManager() {
        return "INSERT INTO pull_task_group_account "
                + "(id, tenant_id, task_id, group_execution_id, account_id, account_phone, "
                + "role_type, role_seq, source_type, selection_mode, entry_mode, membership_status, "
                + "admin_status, availability_status, created_at, updated_at) VALUES "
                + "(101, 7, 1, 11, 901, '8613800000901', 1, 1, 1, 1, 1, 2, 4, 3, 100, 100)";
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_manager_supplement_test");
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
                    "mapper/task/PullTaskStandardSettingMapper.xml",
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

        @Bean PullTaskStandardSettingMapper settingMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskStandardSettingMapper.class);
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

        @Bean AccountGroupService accountGroupService() {
            return mock(AccountGroupService.class);
        }

        @Bean PullTaskExecutionDispatchTrigger dispatchTrigger() {
            return mock(PullTaskExecutionDispatchTrigger.class);
        }

        @Bean
        PullTaskManagerSupplementService supplementService(
                PullTaskMapper taskMapper,
                PullTaskStandardSettingMapper settingMapper,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskAccountActionMapper actionMapper,
                AccountProtocolLookupService accountLookup,
                AccountGroupService accountGroupService,
                PullTaskExecutionDispatchTrigger trigger) {
            return new PullTaskManagerSupplementServiceImpl(
                    taskMapper, settingMapper,
                    new PullTaskManagerSupplementResources(
                            executionMapper, accountMapper, actionMapper,
                            accountLookup, accountGroupService),
                    trigger);
        }
    }
}
