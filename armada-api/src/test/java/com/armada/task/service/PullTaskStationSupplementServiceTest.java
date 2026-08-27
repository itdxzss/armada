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
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskStationSupplementDTO;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupAccountSource;
import com.armada.task.model.enums.PullTaskSelectionMode;
import com.armada.task.model.vo.PullTaskStationSupplementOptionsVO;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import com.armada.task.scheduler.PullTaskStationSelectionService;
import com.armada.task.service.impl.PullTaskStationSupplementResources;
import com.armada.task.service.impl.PullTaskStationSupplementServiceImpl;
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

/** OP-03 补充站台候选、不可变锁定和检查点恢复的真实 Mapper 测试。 */
@SpringJUnitConfig(PullTaskStationSupplementServiceTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskStationSupplementServiceTest {

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PullTaskStationSupplementService service;
    @Autowired private AccountProtocolLookupService accountLookup;
    @Autowired private AccountGroupService accountGroupService;
    @Autowired private PullTaskExecutionDispatchTrigger dispatchTrigger;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        DataScopeContext.open(DataScope.all(501L));
        PullTaskNormalLinkH2Support.resetSchema(
                dataSource, task(), setting(), execution(), usedStation());
        reset(accountLookup, accountGroupService, dispatchTrigger);
        when(accountGroupService.requireExisting(90L)).thenReturn(group(90L));
        when(accountGroupService.requireExisting(91L)).thenReturn(group(91L));
        when(accountLookup.findOnlineNormalByGroupId(90L))
                .thenReturn(List.of(account(911L)));
        when(accountLookup.findOnlineNormalByGroupId(91L))
                .thenReturn(List.of(account(912L), account(913L)));
    }

    @AfterEach
    void tearDown() {
        DataScopeContext.clear();
        TenantContext.clear();
    }

    @Test
    void optionsExposeTheCurrentGapAndOnlyAdditionalCandidates() {
        PullTaskStationSupplementOptionsVO frozenGroup = service.options(1L, 11L, null);
        PullTaskStationSupplementOptionsVO alternateGroup = service.options(1L, 11L, 91L);

        assertThat(frozenGroup.requiredStationCount()).isEqualTo(2);
        assertThat(frozenGroup.missingStationCount()).isEqualTo(1);
        assertThat(frozenGroup.stationGroupId()).isEqualTo(90L);
        assertThat(frozenGroup.candidates()).isEmpty();
        assertThat(alternateGroup.stationGroupId()).isEqualTo(91L);
        assertThat(alternateGroup.candidates())
                .extracting(candidate -> candidate.accountId())
                .containsExactly(912L, 913L);
    }

    @Test
    void manualSupplementLocksOnlyTheStationAndPreservesManualPause() {
        jdbc.update("UPDATE pull_task_group_execution SET manual_paused=1 WHERE id=11");

        service.supplement(1L, 11L, new PullTaskStationSupplementDTO(
                91L, 1, PullTaskSelectionMode.MANUAL.code(), List.of(913L)));

        assertThat(jdbc.queryForMap(
                "SELECT * FROM pull_task_group_account WHERE account_id=913"))
                .satisfies(row -> {
                    assertThat(row.get("ROLE_TYPE"))
                            .isEqualTo(PullTaskGroupAccountRole.STATION.code());
                    assertThat(row.get("SOURCE_TYPE"))
                            .isEqualTo(PullTaskGroupAccountSource.SUPPLEMENT.code());
                    assertThat(row.get("SELECTION_MODE"))
                            .isEqualTo(PullTaskSelectionMode.MANUAL.code());
                    assertThat(row.get("ENTRY_MODE")).isNull();
                    assertThat(row.get("PULL_CALL_ID")).isNull();
                    assertThat(row.get("MEMBERSHIP_STATUS")).isEqualTo(0);
                });
        assertThat(jdbc.queryForMap(
                "SELECT * FROM pull_task_group_execution WHERE id=11"))
                .satisfies(row -> {
                    assertThat(row.get("EXECUTION_STATUS"))
                            .isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
                    assertThat(row.get("STAGE"))
                            .isEqualTo(PullTaskExecutionStage.PULL_EXECUTION.code());
                    assertThat(row.get("MANUAL_PAUSED")).isEqualTo(1);
                });
        verify(dispatchTrigger).dispatchAfterCommit();
    }

    @Test
    void automaticSupplementFreezesTheRequestedNumberOfCandidates() {
        service.supplement(1L, 11L, new PullTaskStationSupplementDTO(
                91L, 1, PullTaskSelectionMode.AUTOMATIC.code(), List.of()));

        assertThat(jdbc.queryForList(
                "SELECT account_id FROM pull_task_group_account "
                        + "WHERE group_execution_id=11 AND source_type=2",
                Long.class)).containsExactly(912L);
    }

    @Test
    void rejectsOverfillAndManualAccountsOutsideCurrentCandidates() {
        assertThatThrownBy(() -> service.supplement(1L, 11L,
                new PullTaskStationSupplementDTO(
                        91L, 2, PullTaskSelectionMode.AUTOMATIC.code(), List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺口");
        assertThatThrownBy(() -> service.supplement(1L, 11L,
                new PullTaskStationSupplementDTO(
                        91L, 1, PullTaskSelectionMode.MANUAL.code(), List.of(999L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("候选");
    }

    private static ProtocolAccountRef account(long id) {
        return new ProtocolAccountRef(
                id, ProtocolBackend.WEB, "acc-" + id, "861380000" + id);
    }

    private static AccountGroup group(long id) {
        AccountGroup row = new AccountGroup();
        row.setId(id);
        row.setName("站台组" + id);
        return row;
    }

    private static String task() {
        return "INSERT INTO pull_task "
                + "(id, tenant_id, owner_user_id, task_type, task_name, mode, status, version, "
                + "config_json, created_at, updated_at) VALUES "
                + "(1, 7, 501, 'STANDARD', 'task', 'NORMAL_LINK', 'EXECUTING', 1, '{}', 100, 100)";
    }

    private static String setting() {
        return "INSERT INTO pull_task_standard_setting "
                + "(task_id, tenant_id, auto_start, material_admin_timing, "
                + "pull_interval_seconds, concurrent_group_count, puller_count_per_group, "
                + "pull_count_min, pull_count_max, station_count_per_call, puller_risk_minutes, "
                + "manager_group_id, puller_group_id, station_group_id, required_manager_count, "
                + "manager_group_name, puller_group_name, station_group_name, created_at, updated_at) "
                + "VALUES (1, 7, 0, 1, 30, 1, 1, 1, 2, 2, 0, 88, 89, 90, 1, "
                + "'manager', 'puller', 'station', 100, 100)";
    }

    private static String execution() {
        return "INSERT INTO pull_task_group_execution "
                + "(id, tenant_id, task_id, seq, normalized_link, invite_code, "
                + "source_link_line_no, source_file_index, source_file_name, group_jid, "
                + "execution_status, stage, manual_paused, wait_resource_type, reason_code, "
                + "next_run_at, version, created_at, updated_at) VALUES "
                + "(11, 7, 1, 1, 'chat.whatsapp.com/AAAA', 'AAAA', 1, 1, 'a.txt', "
                + "'120363group@g.us', 3, 6, 0, 3, 'STATION_UNAVAILABLE', 5000, 2, 100, 100)";
    }

    private static String usedStation() {
        return "INSERT INTO pull_task_group_account "
                + "(id, tenant_id, task_id, group_execution_id, account_id, account_phone, "
                + "role_type, role_seq, source_type, selection_mode, entry_mode, "
                + "membership_status, admin_status, availability_status, pull_call_id, "
                + "created_at, updated_at) VALUES "
                + "(100, 7, 1, 11, 910, '8613800000910', 3, 1, 1, 1, 3, 2, 0, 1, 500, 100, 100)";
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource(
                    "pull_task_station_supplement_test");
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
                    "mapper/task/PullTaskGroupAccountMapper.xml");
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

        @Bean AccountProtocolLookupService accountLookup() {
            return mock(AccountProtocolLookupService.class);
        }

        @Bean AccountGroupService accountGroupService() {
            return mock(AccountGroupService.class);
        }

        @Bean PullTaskExecutionDispatchTrigger dispatchTrigger() {
            return mock(PullTaskExecutionDispatchTrigger.class);
        }

        @Bean PullTaskStationSelectionService stationSelectionService(
                PullTaskGroupAccountMapper accountMapper,
                AccountProtocolLookupService accountLookup) {
            return new PullTaskStationSelectionService(accountMapper, accountLookup);
        }

        @Bean
        PullTaskStationSupplementResources supplementResources(
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskGroupAccountMapper accountMapper,
                AccountProtocolLookupService accountLookup,
                AccountGroupService accountGroupService,
                PullTaskStationSelectionService stationSelectionService) {
            return new PullTaskStationSupplementResources(
                    executionMapper, accountMapper, accountLookup,
                    accountGroupService, stationSelectionService);
        }

        @Bean
        PullTaskStationSupplementService supplementService(
                PullTaskMapper taskMapper,
                PullTaskStandardSettingMapper settingMapper,
                PullTaskStationSupplementResources resources,
                PullTaskExecutionDispatchTrigger trigger) {
            return new PullTaskStationSupplementServiceImpl(
                    taskMapper, settingMapper, resources, trigger);
        }
    }
}
