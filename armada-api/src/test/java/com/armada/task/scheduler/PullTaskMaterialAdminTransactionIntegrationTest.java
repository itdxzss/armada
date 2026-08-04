package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolPullTaskMaterialAdminCommandRequest;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.model.dto.PullTaskExecutionClaimCriteria;
import com.armada.task.model.dto.PullTaskExecutionClaimState;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.dto.PullTaskMaterialPullResult;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
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
import org.mockito.ArgumentCaptor;
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

/** 使用真实 Mapper XML 验证 stage 6 料子提权 Outbox 提交和重启恢复。 */
@SpringJUnitConfig(PullTaskMaterialAdminTransactionIntegrationTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskMaterialAdminTransactionIntegrationTest {

    private static final ProtocolAccountRef MANAGER = new ProtocolAccountRef(
            901L, ProtocolBackend.WEB, "manager-901", "8613800000901");

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskGroupAccountMapper accountMapper;
    @Autowired private PullTaskMaterialMemberMapper materialMapper;
    @Autowired private AccountProtocolLookupService accountLookup;
    @Autowired private ProtocolCommandOutboxService outboxService;
    @Autowired private PullTaskMaterialAdminTransactionService service;

    private long executionId;

    @BeforeEach
    void setUp() throws SQLException {
        reset(accountLookup, outboxService);
        when(accountLookup.findActiveProtocolRefs(List.of(901L))).thenReturn(List.of(MANAGER));
        when(outboxService.enqueuePullTaskMaterialAdminCommands(anyList()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(
                        "pull-task:100", List.of("cmd-admin-1"), 1));
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        execute("INSERT INTO pull_task "
                + "(id, tenant_id, task_type, task_name, mode, status, config_json, "
                + "created_at, updated_at) VALUES "
                + "(100, 7, 'STANDARD', 'task', 'NORMAL_LINK', 'EXECUTING', '{}', 100, 100)");
        PullTaskGroupExecution execution = execution();
        executionMapper.insertDraft(execution);
        executionMapper.freezeDraftRows(100L, 500L);
        executionId = execution.getId();
        execute("UPDATE pull_task_group_execution "
                + "SET execution_status=2, stage=6, version=6, group_jid='120363group@g.us' "
                + "WHERE id=" + executionId);
        PullTaskGroupAccount manager = manager();
        accountMapper.insert(manager);
        accountMapper.updateMembership(manager.getId(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 520L, 520L);
        PullTaskMaterialMember material = material();
        materialMapper.batchInsert(List.of(material));
        Long materialId = materialMapper.selectUnconsumed(executionId, 1).get(0).getId();
        materialMapper.assignToCall(List.of(materialId), 900L, 530L);
        materialMapper.writeBackPullResult(new PullTaskMaterialPullResult(
                materialId,
                PullTaskMaterialPullStatus.SUCCESS.code(),
                PullTaskFactResult.success("8613900000001@s.whatsapp.net", 540L),
                540L));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void submissionPersistsRealOutboxCommandAndReleasesClaim() {
        PullTaskExecutionDispatchResult result = service.prepare(
                claim("worker-1", 600L), "worker-1", 610L);

        assertThat(result).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        TenantContext.set(7L);
        PullTaskMaterialMember material = materialMapper.selectByExecution(executionId).get(0);
        assertThat(material.getAdminStatus())
                .isEqualTo(PullTaskMaterialAdminStatus.SUBMITTED.code());
        assertThat(material.getAdminCommandId()).isEqualTo("cmd-admin-1");
        PullTaskGroupExecution saved = executionMapper.selectByTaskId(100L).get(0);
        assertThat(saved.getStage()).isEqualTo(PullTaskExecutionStage.MATERIAL_ADMIN.code());
        assertThat(saved.getNextRunAt()).isEqualTo(60_610L);
        assertThat(saved.getLockOwner()).isNull();
        ArgumentCaptor<List<ProtocolPullTaskMaterialAdminCommandRequest>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueuePullTaskMaterialAdminCommands(captor.capture());
        ProtocolPullTaskMaterialAdminCommandRequest command = captor.getValue().get(0);
        assertThat(command.materialId()).isEqualTo(material.getId());
        assertThat(command.managerGroupAccountId()).isPositive();
        assertThat(command.actor()).isEqualTo(MANAGER);
    }

    @Test
    void submittedCommandIsDeferredAfterRestartWithoutReplayOrFakeUnknown() {
        service.prepare(claim("worker-1", 600L), "worker-1", 610L);
        PullTaskExecutionDispatchResult recovered = service.prepare(
                claim("worker-2", 70_000L), "worker-2", 70_010L);

        assertThat(recovered).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        TenantContext.set(7L);
        PullTaskMaterialMember material = materialMapper.selectByExecution(executionId).get(0);
        assertThat(material.getAdminStatus())
                .isEqualTo(PullTaskMaterialAdminStatus.SUBMITTED.code());
        assertThat(material.getAdminCommandId()).isEqualTo("cmd-admin-1");
        verify(outboxService, times(1)).enqueuePullTaskMaterialAdminCommands(anyList());
    }

    @Test
    void missingActiveManagerWaitsForResourceWithoutSubmittingCommand() {
        when(accountLookup.findActiveProtocolRefs(List.of(901L))).thenReturn(List.of());

        assertThat(service.prepare(claim("worker-1", 600L), "worker-1", 610L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        TenantContext.set(7L);
        PullTaskGroupExecution saved = executionMapper.selectByTaskId(100L).get(0);
        assertThat(saved.getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(saved.getWaitResourceType()).isEqualTo(PullTaskWaitResourceType.MANAGER.code());
        assertThat(materialMapper.selectByExecution(executionId).get(0).getAdminStatus())
                .isEqualTo(PullTaskMaterialAdminStatus.PENDING.code());
        verify(outboxService, never()).enqueuePullTaskMaterialAdminCommands(anyList());
    }

    private PullTaskGroupExecution claim(String owner, long now) {
        TenantContext.clear();
        executionMapper.claimDue(new PullTaskExecutionClaimCriteria(
                new PullTaskExecutionClaimCriteria.Lease(1, now, owner, now + 500L),
                List.of(new PullTaskExecutionClaimState(
                        PullTaskExecutionStatus.EXECUTING.code(),
                        List.of(PullTaskExecutionStage.MATERIAL_ADMIN.code()))),
                new PullTaskExecutionClaimCriteria.Parent(
                        PullTaskType.STANDARD.name(), "NORMAL_LINK",
                        PullTaskStandardStatus.EXECUTING.name())));
        return executionMapper.selectClaimed(owner, now).get(0);
    }

    private PullTaskGroupExecution execution() {
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

    private PullTaskGroupAccount manager() {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(100L);
        row.setGroupExecutionId(executionId);
        row.setAccountId(901L);
        row.setAccountPhone(MANAGER.wsPhone());
        row.setRoleType(PullTaskGroupAccountRole.MANAGER.code());
        row.setRoleSeq(1);
        row.setSourceType(1);
        row.setSelectionMode(1);
        row.setEntryMode(1);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    private PullTaskMaterialMember material() {
        PullTaskMaterialMember row = new PullTaskMaterialMember();
        row.setGroupExecutionId(executionId);
        row.setMemberSeq(1);
        row.setSourceLineNo(1);
        row.setNormalizedPhone("8613900000001");
        row.setAdminRequired(1);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
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

        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_material_admin_test");
        }

        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean SqlSessionFactory sqlSessionFactory(
                DataSource dataSource, MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskGroupAccountMapper.xml",
                    "mapper/task/PullTaskMaterialMemberMapper.xml");
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

        @Bean PullTaskMaterialMemberMapper materialMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMaterialMemberMapper.class);
        }

        @Bean AccountProtocolLookupService accountLookup() {
            return mock(AccountProtocolLookupService.class);
        }

        @Bean ProtocolCommandOutboxService outboxService() {
            return mock(ProtocolCommandOutboxService.class);
        }

        @Bean PullTaskExecutionDispatchProperties properties() {
            return new PullTaskExecutionDispatchProperties();
        }

        @Bean PullTaskMaterialAdminResources resources(
                PullTaskGroupExecutionMapper mapper,
                AccountProtocolLookupService accountLookup,
                ProtocolCommandOutboxService outboxService,
                PullTaskExecutionDispatchProperties properties) {
            return new PullTaskMaterialAdminResources(
                    mapper, accountLookup, outboxService, properties);
        }

        @Bean PullTaskMaterialAdminTransactionService service(
                PullTaskMapper taskMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskMaterialMemberMapper materialMapper,
                PullTaskMaterialAdminResources resources) {
            return new PullTaskMaterialAdminTransactionService(
                    taskMapper, accountMapper, materialMapper, resources);
        }
    }
}
