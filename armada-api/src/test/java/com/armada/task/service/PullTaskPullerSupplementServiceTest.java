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
import com.armada.task.model.dto.PullTaskPullerSupplementDTO;
import com.armada.task.model.enums.PullTaskAccountEntryMode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupAccountSource;
import com.armada.task.model.enums.PullTaskSelectionMode;
import com.armada.task.model.vo.PullTaskPullerSupplementOptionsVO;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import com.armada.task.service.impl.PullTaskPullerSupplementResources;
import com.armada.task.service.impl.PullTaskPullerSupplementServiceImpl;
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

/** OP-02 补充拉手四种组合、候选过滤和检查点回退的真实 Mapper 测试。 */
@SpringJUnitConfig(PullTaskPullerSupplementServiceTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskPullerSupplementServiceTest {

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PullTaskPullerSupplementService service;
    @Autowired private AccountProtocolLookupService accountLookup;
    @Autowired private AccountGroupService accountGroupService;
    @Autowired private PullTaskExecutionDispatchTrigger dispatchTrigger;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource,
                tasks(), settings(), executions(), roles());
        reset(accountLookup, accountGroupService, dispatchTrigger);
        when(accountGroupService.requireExisting(89L)).thenReturn(accountGroup());
        when(accountLookup.findOnlineNormalByGroupId(89L)).thenReturn(List.of(
                account(901L), account(902L), account(903L), account(904L)));
        when(accountLookup.findActiveProtocolRefs(List.of(800L)))
                .thenReturn(List.of(account(800L)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void optionsExposeFrozenCountsAndExcludeExistingOrCrossTaskOccupiedPullers() {
        PullTaskPullerSupplementOptionsVO options = service.options(1L, 11L, null);

        assertThat(options.currentPullerCount()).isZero();
        assertThat(options.requiredPullerCount()).isEqualTo(2);
        assertThat(options.missingPullerCount()).isEqualTo(2);
        assertThat(options.pullerGroupId()).isEqualTo(89L);
        assertThat(options.managerInviteAvailable()).isTrue();
        assertThat(options.candidates()).extracting(candidate -> candidate.accountId())
                .containsExactly(902L, 904L);
    }

    @Test
    void manualLinkSupplementPersistsFrozenRowsAndPreservesManualPause() {
        jdbc.update("UPDATE pull_task_group_execution SET manual_paused = 1 WHERE id = 11");

        service.supplement(1L, 11L, new PullTaskPullerSupplementDTO(
                89L, 1, PullTaskSelectionMode.MANUAL.code(),
                PullTaskAccountEntryMode.JOIN_BY_LINK.code(), List.of(902L)));

        assertThat(jdbc.queryForMap(
                "SELECT * FROM pull_task_group_account WHERE account_id = 902"))
                .satisfies(row -> {
                    assertThat(row.get("ROLE_TYPE"))
                            .isEqualTo(PullTaskGroupAccountRole.PULLER.code());
                    assertThat(row.get("SOURCE_TYPE"))
                            .isEqualTo(PullTaskGroupAccountSource.SUPPLEMENT.code());
                    assertThat(row.get("SELECTION_MODE"))
                            .isEqualTo(PullTaskSelectionMode.MANUAL.code());
                    assertThat(row.get("ENTRY_MODE"))
                            .isEqualTo(PullTaskAccountEntryMode.JOIN_BY_LINK.code());
                    assertThat(row.get("RELEASED_AT")).isNull();
                });
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pull_task_account_action WHERE action_type = 3",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap(
                "SELECT * FROM pull_task_group_execution WHERE id = 11"))
                .satisfies(row -> {
                    assertThat(row.get("EXECUTION_STATUS"))
                            .isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
                    assertThat(row.get("STAGE"))
                            .isEqualTo(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
                    assertThat(row.get("MANUAL_PAUSED")).isEqualTo(1);
                });
        verify(dispatchTrigger).dispatchAfterCommit();
    }

    @Test
    void automaticManagerInviteFreezesTheRequestedCountWithoutLinkActions() {
        jdbc.update("DELETE FROM pull_task_group_account WHERE group_execution_id = 12");

        service.supplement(1L, 11L, new PullTaskPullerSupplementDTO(
                89L, 2, PullTaskSelectionMode.AUTOMATIC.code(),
                PullTaskAccountEntryMode.MANAGER_INVITE.code(), List.of()));

        assertThat(jdbc.queryForList(
                "SELECT account_id FROM pull_task_group_account "
                        + "WHERE group_execution_id = 11 AND source_type = 2 ORDER BY role_seq",
                Long.class)).containsExactly(902L, 903L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pull_task_account_action WHERE action_type = 3",
                Integer.class)).isZero();
    }

    @Test
    void automaticLinkSupplementFreezesAutoSelectionAndLinkAction() {
        service.supplement(1L, 11L, new PullTaskPullerSupplementDTO(
                89L, 1, PullTaskSelectionMode.AUTOMATIC.code(),
                PullTaskAccountEntryMode.JOIN_BY_LINK.code(), List.of()));

        assertThat(jdbc.queryForMap(
                "SELECT * FROM pull_task_group_account WHERE account_id = 902"))
                .satisfies(row -> {
                    assertThat(row.get("SELECTION_MODE"))
                            .isEqualTo(PullTaskSelectionMode.AUTOMATIC.code());
                    assertThat(row.get("ENTRY_MODE"))
                            .isEqualTo(PullTaskAccountEntryMode.JOIN_BY_LINK.code());
                });
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pull_task_account_action WHERE action_type = 3",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void manualManagerInviteFreezesExplicitSelectionWithoutLinkAction() {
        service.supplement(1L, 11L, new PullTaskPullerSupplementDTO(
                89L, 1, PullTaskSelectionMode.MANUAL.code(),
                PullTaskAccountEntryMode.MANAGER_INVITE.code(), List.of(902L)));

        assertThat(jdbc.queryForMap(
                "SELECT * FROM pull_task_group_account WHERE account_id = 902"))
                .satisfies(row -> {
                    assertThat(row.get("SELECTION_MODE"))
                            .isEqualTo(PullTaskSelectionMode.MANUAL.code());
                    assertThat(row.get("ENTRY_MODE"))
                            .isEqualTo(PullTaskAccountEntryMode.MANAGER_INVITE.code());
                });
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pull_task_account_action WHERE action_type = 3",
                Integer.class)).isZero();
    }

    @Test
    void rejectsOverfillAndManualSelectionsOutsideTheCurrentCandidates() {
        assertThatThrownBy(() -> service.supplement(1L, 11L,
                new PullTaskPullerSupplementDTO(
                        89L, 3, PullTaskSelectionMode.AUTOMATIC.code(),
                        PullTaskAccountEntryMode.JOIN_BY_LINK.code(), List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺口");

        assertThatThrownBy(() -> service.supplement(1L, 11L,
                new PullTaskPullerSupplementDTO(
                        89L, 1, PullTaskSelectionMode.MANUAL.code(),
                        PullTaskAccountEntryMode.JOIN_BY_LINK.code(), List.of(999L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("候选");
    }

    private static ProtocolAccountRef account(long id) {
        return new ProtocolAccountRef(
                id, ProtocolBackend.WEB, "acc-" + id, "861380000" + id);
    }

    private static AccountGroup accountGroup() {
        AccountGroup row = new AccountGroup();
        row.setId(89L);
        row.setName("拉手组");
        return row;
    }

    private static String tasks() {
        return "INSERT INTO pull_task "
                + "(id, tenant_id, task_type, task_name, mode, status, version, "
                + "config_json, created_at, updated_at) VALUES "
                + "(1, 7, 'STANDARD', 'task', 'NORMAL_LINK', 'EXECUTING', 1, '{}', 100, 100),"
                + "(2, 7, 'STANDARD', 'other', 'NORMAL_LINK', 'EXECUTING', 1, '{}', 100, 100)";
    }

    private static String settings() {
        return "INSERT INTO pull_task_standard_setting "
                + "(task_id, tenant_id, auto_start, material_admin_timing, pull_interval_seconds, "
                + "concurrent_group_count, puller_count_per_group, pull_count_min, pull_count_max, "
                + "station_count_per_call, puller_risk_minutes, manager_group_id, puller_group_id, "
                + "station_group_id, required_manager_count, manager_group_name, puller_group_name, "
                + "station_group_name, created_at, updated_at) VALUES "
                + "(1, 7, 0, 1, 30, 1, 2, 1, 2, 0, 0, 88, 89, 90, 1, "
                + "'manager', 'puller', 'station', 100, 100),"
                + "(2, 7, 0, 1, 30, 1, 1, 1, 2, 0, 0, 88, 89, 90, 1, "
                + "'manager', 'puller', 'station', 100, 100)";
    }

    private static String executions() {
        return "INSERT INTO pull_task_group_execution "
                + "(id, tenant_id, task_id, seq, normalized_link, invite_code, source_link_line_no, "
                + "source_file_index, source_file_name, group_jid, execution_status, stage, "
                + "manual_paused, wait_resource_type, reason_code, next_run_at, version, "
                + "created_at, updated_at) VALUES "
                + "(11, 7, 1, 1, 'chat.whatsapp.com/AAAA', 'AAAA', 1, 1, 'a.txt', "
                + "'120363group@g.us', 3, 5, 0, 2, 'PULLER_UNAVAILABLE', 5000, 2, 100, 100),"
                + "(12, 7, 2, 1, 'chat.whatsapp.com/BBBB', 'BBBB', 1, 1, 'b.txt', "
                + "'120363other@g.us', 2, 5, 0, NULL, NULL, 0, 2, 100, 100)";
    }

    private static String roles() {
        return "INSERT INTO pull_task_group_account "
                + "(id, tenant_id, task_id, group_execution_id, account_id, account_phone, "
                + "role_type, role_seq, source_type, selection_mode, entry_mode, membership_status, "
                + "admin_status, availability_status, occupied_at, released_at, created_at, updated_at) VALUES "
                + "(100, 7, 1, 11, 800, '8613800000800', 1, 1, 1, 1, 1, 2, 3, 1, NULL, NULL, 100, 100),"
                + "(101, 7, 1, 11, 901, '8613800000901', 2, 1, 1, 1, 2, 0, 0, 3, 100, 200, 100, 200),"
                + "(102, 7, 2, 12, 903, '8613800000903', 2, 1, 1, 1, 2, 0, 0, 1, 100, NULL, 100, 100)";
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_puller_supplement_test");
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
        PullTaskPullerSupplementService supplementService(
                PullTaskMapper taskMapper,
                PullTaskStandardSettingMapper settingMapper,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskAccountActionMapper actionMapper,
                AccountProtocolLookupService accountLookup,
                AccountGroupService accountGroupService,
                PullTaskExecutionDispatchTrigger trigger) {
            return new PullTaskPullerSupplementServiceImpl(
                    taskMapper, settingMapper,
                    new PullTaskPullerSupplementResources(
                            executionMapper, accountMapper, actionMapper,
                            accountLookup, accountGroupService),
                    trigger);
        }
    }
}
