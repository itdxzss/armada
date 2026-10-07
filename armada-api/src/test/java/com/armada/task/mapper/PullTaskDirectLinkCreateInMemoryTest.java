package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.account.model.entity.AccountGroup;
import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.group.service.GroupFolderService;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskDirectLinkCreateDTO;
import com.armada.task.model.dto.PullTaskSimpleNewGroupCreateDTO;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.service.PullTaskGroupAvatarService;
import com.armada.task.service.impl.PullTaskDataPackageSourceService;
import com.armada.task.service.impl.PullTaskDirectLinkCreateTransactionService;
import com.armada.task.service.impl.PullTaskDirectLinkPlanner;
import com.armada.task.service.impl.PullTaskStandardCreateResources;
import com.armada.task.service.impl.PullTaskStandardGroupSettingWriter;
import com.armada.task.service.impl.PullTaskStandardSettingWriter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 使用真实 Mapper、租户插件和事务证明正式创建、占用冲突回滚与幂等。 */
class PullTaskDirectLinkCreateInMemoryTest {
    private static final String LINK = "https://chat.whatsapp.com/ABCDEFGHIJKLMNOPQRSTUV";
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private PullTaskDirectLinkCreateMapper tasks;
    private PullTaskGroupExecutionMapper executions;
    private PullTaskStandardSettingMapper settings;
    private PullTaskStandardGroupSettingMapper groupSettings;
    private GroupLinkRegistryService registry;
    private PullTaskDirectLinkCreateTransactionService service;

    @BeforeEach
    void setup() throws Exception {
        DataSource ds = PullTaskNormalLinkH2Support.dataSource("direct_create_" + UUID.randomUUID());
        PullTaskNormalLinkH2Support.resetSchema(ds);
        jdbc = new JdbcTemplate(ds);
        var config = new MyBatisConfig();
        var factory = PullTaskNormalLinkH2Support.sqlSessionFactory(ds,
                config.mybatisPlusInterceptor(config.tenantLineHandler()),
                "mapper/task/PullTaskDirectLinkCreateMapper.xml", "mapper/task/PullTaskGroupExecutionMapper.xml",
                "mapper/task/PullTaskMaterialMemberMapper.xml", "mapper/task/PullTaskStandardSettingMapper.xml",
                "mapper/task/PullTaskStandardGroupSettingMapper.xml");
        var sessions = new SqlSessionTemplate(factory);
        tasks = sessions.getMapper(PullTaskDirectLinkCreateMapper.class);
        executions = sessions.getMapper(PullTaskGroupExecutionMapper.class);
        settings = sessions.getMapper(PullTaskStandardSettingMapper.class);
        groupSettings = sessions.getMapper(PullTaskStandardGroupSettingMapper.class);
        var accountGroups = mock(AccountGroupService.class);
        var pullers = new AccountGroup();
        pullers.setId(12L);
        pullers.setName("拉手分组");
        when(accountGroups.requireExisting(12L)).thenReturn(pullers);
        for (long id : List.of(13L, 14L)) {
            var group = new AccountGroup();
            group.setId(id);
            group.setName("分组" + id);
            when(accountGroups.requireExisting(id)).thenReturn(group);
        }
        registry = mock(GroupLinkRegistryService.class);
        when(registry.registerPullTaskTargets(any(), anyLong())).thenReturn(Map.of(LINK, 9L));
        var resources = new PullTaskStandardCreateResources(
                new PullTaskStandardSettingWriter(settings, accountGroups, mock(GroupFolderService.class),
                        mock(AccountProtocolLookupService.class),
                        mock(com.armada.account.service.AccountCreatorDeletionService.class)),
                new PullTaskStandardGroupSettingWriter(groupSettings), mock(PullTaskGroupAvatarService.class),
                registry, mock(PullTaskDataPackageSourceService.class));
        service = new PullTaskDirectLinkCreateTransactionService(tasks, executions,
                sessions.getMapper(PullTaskMaterialMemberMapper.class), resources);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        TenantContext.set(7L);
    }

    @AfterEach
    void clean() { TenantContext.clear(); }

    @Test
    void createsOnlyFormalRowsWithoutManagerOrAdminWork() {
        var created = create(request(UUID.randomUUID().toString()));
        assertThat(created.getCreationMode()).isEqualTo(PullTaskCreationMode.DIRECT_LINK);
        assertThat(created.getStatus()).isEqualTo("WAIT_START");
        var execution = executions.selectByTaskId(created.getId()).get(0);
        assertThat(execution.getExecutionStatus()).isEqualTo(1);
        assertThat(execution.getStage()).isEqualTo(PullTaskExecutionStage.DIRECT_PULLER_JOIN.code());
        assertThat(execution.getGroupLinkId()).isEqualTo(9L);
        var setting = settings.selectByTaskId(created.getId());
        assertThat(setting.getManagerGroupId()).isNull();
        assertThat(setting.getManagerGroupName()).isNull();
        assertThat(setting.getRequiredManagerCount()).isZero();
        assertThat(setting.getCreatorLeaveAfterPull()).isZero();
        assertThat(setting.getClearExistingMembers()).isZero();
        assertThat(setting.getPullerJoinByLink()).isEqualTo(1);
        assertThat(groupSettings.selectByTaskId(created.getId()).getGroupSettingEnabled()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pull_task WHERE status='DRAFT'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pull_task_material_member WHERE admin_required<>0 OR admin_status<>0", Integer.class)).isZero();
    }

    @Test
    void simpleNewGroupCreatesWithoutLinksAndFreezesTakeoverButNoContactsOrPromotion() {
        var request = new PullTaskSimpleNewGroupCreateDTO(UUID.randomUUID().toString(), "精简建群", null, 0,
                List.of(), 1, 0, 10, 50, 20, 2, 0, 1, 12L, null, 12L,
                13L, 14L, 14L, true, "业务群", null, null, 30);
        PullTaskDirectLinkPlanner.validate(request);
        var plan = row();
        plan.execution().setNormalizedLink(null);
        plan.execution().setInviteCode(null);
        var principal = new AuthPrincipal(2L, 7L, "operator", "操作员", "t", "租户", List.of(), List.of());
        var created = tx.execute(status -> service.create(request, principal, List.of(plan)));
        assertThat(created.getCreationMode()).isEqualTo(PullTaskCreationMode.SIMPLE_NEW_GROUP);
        var stored = tasks.selectByRequest(2L, request.requestId());
        assertThat(stored.getCreatorDeleteAfterTakeover()).isEqualTo(1);
        var execution = executions.selectByTaskId(created.getId()).get(0);
        assertThat(execution.getStage()).isEqualTo(PullTaskExecutionStage.GROUP_CREATE.code());
        assertThat(execution.getGroupSubject()).isEqualTo("业务群-1");
        assertThat(execution.getGroupLinkId()).isNull();
        assertThat(execution.getNormalizedLink()).isNull();
        var setting = settings.selectByTaskId(created.getId());
        assertThat(setting.getManagerGroupId()).isEqualTo(14L);
        assertThat(setting.getCreatorGroupId()).isEqualTo(13L);
        assertThat(setting.getPullCountMin()).isEqualTo(10);
        assertThat(setting.getPullCountMax()).isEqualTo(50);
        assertThat(setting.getPullIntervalSeconds()).isEqualTo(20);
        assertThat(setting.getPullIntervalMaxSeconds()).isEqualTo(30);
        assertThat(setting.getCreatorLeaveAfterPull()).isZero();
        assertThat(setting.getClearExistingMembers()).isZero();
        assertThat(setting.getPullerJoinByLink()).isEqualTo(1);
        assertThat(groupSettings.selectByTaskId(created.getId()).getGroupSettingEnabled()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pull_task WHERE status='DRAFT'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pull_task_material_member WHERE admin_required<>0 OR admin_status<>0", Integer.class)).isZero();
        org.mockito.Mockito.verifyNoInteractions(registry);
    }

    @Test
    void invalidSuffixedGroupNameRollsBackTheWholeFormalTask() {
        var request = new PullTaskSimpleNewGroupCreateDTO(UUID.randomUUID().toString(), "精简建群", null, 0,
                List.of(), 1, 0, 1, 3, 10, 2, 0, 1, 12L, null, null,
                13L, 14L, null, false, "x".repeat(100), null, null, 15);
        var principal = new AuthPrincipal(2L, 7L, "operator", "操作员", "t", "租户", List.of(), List.of());
        assertThatThrownBy(() -> tx.execute(status -> service.create(request, principal, List.of(row()))))
                .hasMessageContaining("追加序号");
        for (String table : List.of("pull_task", "pull_task_standard_setting", "pull_task_standard_group_setting",
                "pull_task_group_execution", "pull_task_material_member")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
        }
    }

    @Test
    void simpleNewGroupMigrationPreservesExistingModeAndCreationRequest() throws Exception {
        var request = request(UUID.randomUUID().toString());
        var existing = create(request);
        var migration = new org.springframework.core.io.ClassPathResource(
                "db/migration/V213__pull_task_simple_new_group.sql");
        jdbc.execute(migration.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(tasks.selectByRequest(2L, request.requestId()).getId()).isEqualTo(existing.getId());
        assertThat(tasks.selectByRequest(2L, request.requestId()).getCreationMode()).isEqualTo(PullTaskCreationMode.DIRECT_LINK);
    }

    @Test
    void retryReturnsSameTaskAndRequestCannotCrossUserOrTenant() {
        var request = request(UUID.randomUUID().toString());
        var first = create(request);
        assertThat(create(request).getId()).isEqualTo(first.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pull_task", Integer.class)).isEqualTo(1);
        assertThat(tasks.selectByRequest(99L, request.requestId())).isNull();
        TenantContext.set(8L);
        assertThat(tasks.selectByRequest(2L, request.requestId())).isNull();
    }

    @Test
    void occupiedLinkRollsBackFormalTaskSettingsAndMaterials() {
        create(request(UUID.randomUUID().toString()));
        var conflicting = request(UUID.randomUUID().toString());
        assertThatThrownBy(() -> create(conflicting)).isInstanceOf(DuplicateKeyException.class);
        assertThat(tasks.selectByRequest(2L, conflicting.requestId())).isNull();
        for (String table : List.of("pull_task", "pull_task_standard_setting", "pull_task_standard_group_setting",
                "pull_task_group_execution", "pull_task_material_member")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void missingRegisteredGroupRollsBackEverything() {
        when(registry.registerPullTaskTargets(any(), anyLong())).thenReturn(Map.of());
        assertThatThrownBy(() -> create(request(UUID.randomUUID().toString()))).hasMessageContaining("群链接");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pull_task", Integer.class)).isZero();
    }

    @Test
    void directLinkTaskProtectsSelectedFolderWhileWaiting() {
        var created = create(request(UUID.randomUUID().toString()));
        jdbc.update("UPDATE pull_task_standard_setting SET source_group_folder_id=18 WHERE task_id=?", created.getId());
        assertThat(executions.countTasksUsingFolders(List.of(18L), List.of("WAIT_START"))).isEqualTo(1);
        TenantContext.set(8L);
        assertThat(executions.countTasksUsingFolders(List.of(18L), List.of("WAIT_START"))).isZero();
    }

    private PullTask create(PullTaskDirectLinkCreateDTO request) {
        var principal = new AuthPrincipal(2L, 7L, "operator", "操作员", "t", "租户", List.of(), List.of());
        return tx.execute(status -> service.create(request, principal, List.of(row())));
    }

    private PullTaskDirectLinkPlanner.PlannedRow row() {
        var row = new PullTaskGroupExecution();
        row.setSeq(1);
        row.setNormalizedLink(LINK);
        row.setInviteCode("ABCDEFGHIJKLMNOPQRSTUV");
        row.setSourceFileIndex(1);
        row.setSourceFileName("members.txt");
        row.setTotalLineCount(1);
        row.setValidMemberCount(1);
        row.setInvalidLineCount(0);
        row.setDuplicateLineCount(0);
        var member = new PullTaskMaterialMember();
        member.setMemberSeq(1);
        member.setSourceLineNo(1);
        member.setNormalizedPhone("919876543210");
        member.setAdminRequired(1);
        return new PullTaskDirectLinkPlanner.PlannedRow(row, List.of(member));
    }

    private PullTaskDirectLinkCreateDTO request(String id) {
        return new PullTaskDirectLinkCreateDTO(id, "新模式", null, 0, null, LINK, List.of(),
                1, 2, 3, 5, 15, 2, 0, 1, 12L, null, null);
    }
}
