package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.model.entity.AccountGroup;
import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.group.service.GroupFolderService;
import com.armada.group.model.vo.GroupFolderOptionVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.model.dto.PullTaskStandardCreateDTO;
import com.armada.task.model.dto.PullTaskStandardGroupSettingDTO;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.vo.PullTaskStandardCreatedVO;
import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskDisappearingMessageMode;
import com.armada.task.model.enums.PullTaskEditPermissionMode;
import com.armada.task.model.enums.PullTaskGroupSettingTiming;
import com.armada.task.model.enums.PullTaskLinkPermissionMode;
import com.armada.task.model.enums.PullTaskMuteMode;
import com.armada.task.model.enums.PullTaskPullerSyncMode;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.task.service.impl.PullTaskStandardCreateServiceImpl;
import com.armada.task.service.impl.PullTaskStandardDraftServiceImpl;
import com.armada.task.service.impl.PullTaskStandardDraftWriter;
import com.armada.task.service.impl.PullTaskStandardDraftWriter.AppendRow;
import com.armada.task.service.impl.PullTaskStandardSettingWriter;
import com.armada.task.service.impl.PullTaskStandardGroupSettingWriter;
import com.armada.task.service.impl.PullTaskStandardCreateTransactionService;
import com.armada.task.service.impl.PullTaskStandardStartServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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

/** 普通群链接任务提交冻结的 H2 集成测试。 */
@SpringJUnitConfig(PullTaskStandardCreateServiceTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskStandardCreateServiceTest {

    private static final long CREATOR = 501L;
    private static final long OTHER_CREATOR = 602L;
    private static final String OPERATOR = "运营甲";
    private static final String LINK_A = "chat.whatsapp.com/AAAAAAAAAAAAAAAAAAAAAA";
    private static final String LINK_B = "chat.whatsapp.com/BBBBBBBBBBBBBBBBBBBBBB";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskStandardCreateService service;

    @Autowired
    private PullTaskStandardDraftWriter writer;

    @Autowired
    private PullTaskMapper pullTaskMapper;

    @Autowired
    private PullTaskGroupExecutionMapper executionMapper;

    @Autowired
    private PullTaskStandardSettingMapper settingMapper;

    @Autowired
    private PullTaskStandardGroupSettingMapper groupSettingMapper;

    @Autowired
    private AccountGroupService accountGroupService;

    @Autowired
    private PullTaskGroupAvatarService avatarService;

    @Autowired
    private AtomicBoolean registryFailure;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        registryFailure.set(false);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void submitFreezesRowsWritesSettingAndFlipsTaskToWaitStart() {
        long taskId = seedDraftWithTwoRows(CREATOR);

        service.create(validRequest(taskId), CREATOR);

        PullTask task = pullTaskMapper.selectLifecycle(taskId);
        assertThat(task.getStatus()).isEqualTo("WAIT_START");
        assertThat(task.getVersion()).isEqualTo(2);
        assertThat(task.getGroupCount()).isEqualTo(2);
        // expected_pull_count 是全部执行行 valid_member_count 之和。
        assertThat(task.getExpectedPullCount()).isEqualTo(2);
        assertThat(executionMapper.selectByTaskId(taskId))
                .allSatisfy(row -> {
                    assertThat(row.getExecutionStatus()).isEqualTo(1);
                    assertThat(row.getGroupLinkId()).isNotNull();
                    assertThat(row.getGroupSubject()).isNull();
                });
        PullTaskStandardSetting setting = settingMapper.selectByTaskId(taskId);
        assertThat(setting.getEarlyPullCount()).isEqualTo(1);
        assertThat(setting.getEarlyPullCallCount()).isEqualTo(2);
        assertThat(setting.getRequiredManagerCount()).isZero();
        assertThat(setting.getCreatorLeaveAfterPull()).isZero();
        assertThat(groupSettingMapper.selectByTaskId(taskId).getGroupName()).isEqualTo("客户群");
    }

    @Test
    void resourcePoolSubmitRequiresFolderAndKeepsTxtRowsUnbound() {
        long taskId = seedResourcePoolDraft(CREATOR);

        service.create(resourcePoolRequest(taskId, 18L), CREATOR);

        PullTask task = pullTaskMapper.selectLifecycle(taskId);
        assertThat(task.getCreationMode()).isEqualTo(PullTaskCreationMode.RESOURCE_POOL);
        PullTaskStandardSetting setting = settingMapper.selectByTaskId(taskId);
        assertThat(setting.getSourceGroupFolderId()).isEqualTo(18L);
        assertThat(executionMapper.selectByTaskId(taskId)).allSatisfy(row -> {
            assertThat(row.getExecutionStatus()).isEqualTo(1);
            assertThat(row.getStage()).isEqualTo(PullTaskExecutionStage.MANAGER_JOIN.code());
            assertThat(row.getGroupSubject()).isNull();
            assertThat(row.getGroupLinkId()).isNull();
            assertThat(row.getGroupJid()).isNull();
            assertThat(row.getNormalizedLink()).isNull();
        });
    }

    @Test
    void resourcePoolSubmitRejectsMissingFolder() {
        long taskId = seedResourcePoolDraft(CREATOR);

        assertThatThrownBy(() -> service.create(resourcePoolRequest(taskId, null), CREATOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("资源池");
    }

    @Test
    void autoStartUsesTheSharedStartServiceAfterFreezingTheTask() {
        long taskId = seedDraftWithTwoRows(CREATOR);

        PullTaskStandardCreatedVO created = service.create(
                withAutoStart(validRequest(taskId), 1), CREATOR);

        assertThat(created.status()).isEqualTo("EXECUTING");
        assertThat(pullTaskMapper.selectLifecycle(taskId).getStatus()).isEqualTo("EXECUTING");
    }

    @Test
    void repeatedAutoStartSubmissionRetriesStartingACommittedWaitingTask() {
        PullTaskStandardCreateTransactionService transactionService =
                mock(PullTaskStandardCreateTransactionService.class);
        PullTaskMapper taskMapper = mock(PullTaskMapper.class);
        PullTaskStandardSettingMapper standardSettingMapper =
                mock(PullTaskStandardSettingMapper.class);
        PullTaskStandardStartService startService = mock(PullTaskStandardStartService.class);
        PullTaskStandardCreateService retryableService = new PullTaskStandardCreateServiceImpl(
                transactionService, taskMapper, standardSettingMapper, startService);
        PullTaskStandardCreateDTO request = withAutoStart(validRequest(9L), 1);
        PullTaskStandardCreatedVO waiting =
                new PullTaskStandardCreatedVO(9L, "普通任务", "WAIT_START", 2, 2);
        PullTask executing = new PullTask();
        executing.setId(9L);
        executing.setTaskName("普通任务");
        executing.setStatus("EXECUTING");
        executing.setGroupCount(2);
        executing.setExpectedPullCount(2);
        when(transactionService.submit(request, CREATOR))
                .thenReturn(new PullTaskStandardCreateTransactionService.SubmissionResult(
                        waiting, false));
        PullTaskStandardSetting savedSetting = new PullTaskStandardSetting();
        savedSetting.setAutoStart(1);
        when(standardSettingMapper.selectByTaskId(9L)).thenReturn(savedSetting);
        when(taskMapper.selectLifecycle(9L)).thenReturn(executing);

        PullTaskStandardCreatedVO result = retryableService.create(request, CREATOR);

        verify(startService).start(9L);
        assertThat(result.status()).isEqualTo("EXECUTING");
    }

    @Test
    void submitRollsBackEntirelyWhenAnyLinkIsAlreadyOccupied() {
        long occupiedTaskId = seedDraftWithTwoRows(CREATOR);
        executionMapper.freezeDraftRows(occupiedTaskId, 800L);
        long taskId = seedDraftWithTwoRows(OTHER_CREATOR);

        assertThatThrownBy(() -> service.create(validRequest(taskId), OTHER_CREATOR))
                .isInstanceOf(BusinessException.class);

        // 整单回滚：草稿完整保留，可继续编辑。
        PullTask task = pullTaskMapper.selectLifecycle(taskId);
        assertThat(task.getStatus()).isEqualTo("DRAFT");
        assertThat(executionMapper.selectByTaskId(taskId))
                .allSatisfy(row -> assertThat(row.getExecutionStatus()).isZero());
        assertThat(settingMapper.selectByTaskId(taskId)).isNull();
        assertThat(groupSettingMapper.selectByTaskId(taskId)).isNull();
    }

    @Test
    void repeatedSubmissionReturnsTheSameTaskWithoutCreatingASecondOne() {
        long taskId = seedDraftWithTwoRows(CREATOR);
        service.create(validRequest(taskId), CREATOR);

        PullTaskStandardCreatedVO second = service.create(validRequest(taskId), CREATOR);

        assertThat(second.id()).isEqualTo(taskId);
        assertThat(pullTaskMapper.selectLifecycle(taskId).getVersion()).isEqualTo(2);
    }

    @Test
    void submitIsRejectedWhenDraftHasNoExecutionRow() {
        long taskId = writer.ensureDraft(CREATOR, OPERATOR, 100L).getId();

        assertThatThrownBy(() -> service.create(validRequest(taskId), CREATOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("群链接");
    }

    @Test
    void submitIsRejectedForAnotherUsersDraft() {
        long taskId = seedDraftWithTwoRows(CREATOR);

        assertThatThrownBy(() -> service.create(validRequest(taskId), OTHER_CREATOR))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void newGroupModeFreezesCreationModeAndCreatorConfigOnSubmit() {
        long taskId = seedNewGroupDraft(CREATOR);
        AccountGroup creatorGroup = new AccountGroup();
        creatorGroup.setName("建群人组");
        when(accountGroupService.requireExisting(16L)).thenReturn(creatorGroup);

        service.create(newGroupRequest(taskId), CREATOR);

        // mode 必须仍是 NORMAL_LINK：它是执行链路开关，改掉调度器就不认领执行行了。
        PullTask task = pullTaskMapper.selectLifecycle(taskId);
        assertThat(task.getMode()).isEqualTo("NORMAL_LINK");
        assertThat(task.getCreationMode()).isEqualTo(PullTaskCreationMode.NEW_GROUP);
        PullTaskStandardSetting setting = settingMapper.selectByTaskId(taskId);
        assertThat(setting.getCreatorGroupId()).isEqualTo(16L);
        assertThat(setting.getCreatorGroupName()).isEqualTo("建群人组");
        assertThat(setting.getInitialStationCount()).isEqualTo(2);
        assertThat(setting.getCreatorLeaveAfterPull()).isOne();
        assertThat(setting.getEarlyPullCallCount()).isZero();
        assertThat(setting.getPullCountMin()).isEqualTo(1);
        assertThat(setting.getPullCountMax()).isEqualTo(3);
        assertThat(setting.getPullIntervalSeconds()).isEqualTo(10);
        assertThat(setting.getPullIntervalMaxSeconds()).isEqualTo(15);
        assertThat(groupSettingMapper.selectByTaskId(taskId).getGroupDescription())
                .isEqualTo("完整群简介\n第二行");
        assertThat(executionMapper.selectByTaskId(taskId)).allSatisfy(row -> {
            assertThat(row.getExecutionStatus()).isEqualTo(1);
            assertThat(row.getStage()).isEqualTo(PullTaskExecutionStage.GROUP_CREATE.code());
            assertThat(row.getGroupLinkId()).isNull();
            assertThat(row.getNormalizedLink()).isNull();
            assertThat(row.getGroupSubject()).isEqualTo("客户群-" + row.getSeq());
        });
    }

    @Test
    void newGroupNamesFollowSequenceAndStayFrozenOnRepeatedSubmission() {
        long taskId = seedNewGroupDraft(CREATOR);
        for (int seq = 3; seq <= 10; seq++) {
            writer.append(taskId, List.of(newGroupAppendRow(seq, seq + ".txt", "8613800138003")), 200L);
        }
        PullTaskStandardCreateDTO request = withGroupName(newGroupRequest(taskId), " 测试群1 ");

        service.create(request, CREATOR);
        service.create(withGroupName(request, "另一个名称"), CREATOR);

        assertThat(executionMapper.selectByTaskId(taskId))
                .extracting(PullTaskGroupExecution::getGroupSubject)
                .containsExactly("测试群1-1", "测试群1-2", "测试群1-3", "测试群1-4", "测试群1-5",
                        "测试群1-6", "测试群1-7", "测试群1-8", "测试群1-9", "测试群1-10");
        assertThat(groupSettingMapper.selectByTaskId(taskId).getGroupName()).isEqualTo("测试群1");
    }

    @Test
    void newGroupNameLimitIncludesActualSequenceEvenWhenDraftHasGaps() {
        long taskId = seedNewGroupDraft(CREATOR);
        writer.append(taskId, List.of(newGroupAppendRow(10, "ten.txt", "8613800138003")), 200L);
        String baseName = "群".repeat(97);

        service.create(withGroupName(newGroupRequest(taskId), baseName), CREATOR);

        assertThat(executionMapper.selectByTaskId(taskId))
                .extracting(PullTaskGroupExecution::getGroupSubject)
                .containsExactly(baseName + "-1", baseName + "-2", baseName + "-10");
    }

    @Test
    void tooLongNumberedNameRollsBackAllNamesSettingsAndTaskSubmission() {
        long taskId = seedNewGroupDraft(CREATOR);
        writer.append(taskId, List.of(newGroupAppendRow(10, "ten.txt", "8613800138003")), 200L);

        assertThatThrownBy(() -> service.create(
                withGroupName(newGroupRequest(taskId), "群".repeat(98)), CREATOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("追加序号后");

        assertThat(pullTaskMapper.selectLifecycle(taskId).getStatus()).isEqualTo("DRAFT");
        assertThat(groupSettingMapper.selectByTaskId(taskId)).isNull();
        assertThat(settingMapper.selectByTaskId(taskId)).isNull();
        assertThat(executionMapper.selectByTaskId(taskId)).allSatisfy(row -> {
            assertThat(row.getGroupSubject()).isNull();
            assertThat(row.getExecutionStatus()).isZero();
        });
    }

    @Test
    void numberedSubjectWriteCannotCrossTenantTaskOrFrozenStatus() {
        long taskId = seedNewGroupDraft(CREATOR);
        long rowId = executionMapper.selectByTaskId(taskId).get(0).getId();
        int draftStatus = PullTaskExecutionStatus.DRAFT.code();
        try {
            TenantContext.set(8L);
            assertThat(executionMapper.updateDraftGroupSubject(rowId, taskId, "错误群名", draftStatus, 300L))
                    .isZero();
        } finally {
            TenantContext.set(7L);
        }
        assertThat(executionMapper.updateDraftGroupSubject(rowId, taskId + 1, "错误群名", draftStatus, 300L))
                .isZero();
        assertThat(executionMapper.selectById(rowId).getGroupSubject()).isNull();

        service.create(newGroupRequest(taskId), CREATOR);

        assertThat(executionMapper.updateDraftGroupSubject(rowId, taskId, "错误群名", draftStatus, 400L))
                .isZero();
        assertThat(executionMapper.selectById(rowId).getGroupSubject()).isEqualTo("客户群-1");
    }

    @Test
    void newGroupModeAllowsNoManagerGroupAndPersistsNullSnapshot() {
        long taskId = seedNewGroupDraft(CREATOR);
        AccountGroup creatorGroup = new AccountGroup();
        creatorGroup.setName("建群人组");
        when(accountGroupService.requireExisting(16L)).thenReturn(creatorGroup);

        service.create(withManagerGroup(newGroupRequest(taskId), null), CREATOR);

        assertThat(pullTaskMapper.selectLifecycle(taskId).getStatus()).isEqualTo("WAIT_START");
        PullTaskStandardSetting setting = settingMapper.selectByTaskId(taskId);
        assertThat(setting.getManagerGroupId()).isNull();
        assertThat(setting.getManagerGroupName()).isNull();
        assertThat(setting.getCreatorGroupId()).isEqualTo(16L);
        assertThat(setting.getPullerGroupId()).isEqualTo(12L);
        assertThat(executionMapper.selectByTaskId(taskId)).allSatisfy(row ->
                assertThat(row.getStage()).isEqualTo(PullTaskExecutionStage.GROUP_CREATE.code()));
    }

    @Test
    void linkModesStillRejectNoManagerGroupWithoutFreezingDraft() {
        long linkTaskId = seedDraftWithTwoRows(CREATOR);
        assertThatThrownBy(() -> service.create(
                withManagerGroup(validRequest(linkTaskId), null), CREATOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("管理");
        assertThat(pullTaskMapper.selectLifecycle(linkTaskId).getStatus()).isEqualTo("DRAFT");
        assertThat(settingMapper.selectByTaskId(linkTaskId)).isNull();

        long poolTaskId = seedResourcePoolDraft(CREATOR);
        assertThatThrownBy(() -> service.create(
                withManagerGroup(resourcePoolRequest(poolTaskId, 18L), null), CREATOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("管理");
        assertThat(pullTaskMapper.selectLifecycle(poolTaskId).getStatus()).isEqualTo("DRAFT");
        assertThat(settingMapper.selectByTaskId(poolTaskId)).isNull();
    }

    @Test
    void newGroupModeRejectsAnOmittedGroupSettingSwitchBeforeFreezingAnything() {
        long taskId = seedNewGroupDraft(CREATOR);
        AccountGroup creatorGroup = new AccountGroup();
        creatorGroup.setName("建群人组");
        when(accountGroupService.requireExisting(16L)).thenReturn(creatorGroup);
        PullTaskStandardGroupSettingDTO current = newGroupRequest(taskId).groupSetting();
        PullTaskStandardGroupSettingDTO withoutExplicitSwitch =
                new PullTaskStandardGroupSettingDTO(
                        null, current.settingTiming(), current.groupName(),
                        current.useMaterialFileNameAsGroupName(), current.avatarFileKey(),
                        current.groupDescription(), current.autoCloseMuteAfterTask(),
                        current.autoCloseInviteAfterTask(), current.editPermission(),
                        current.muteMode(), current.linkPermission(),
                        current.disappearingMessage());

        assertThatThrownBy(() -> service.create(withGroupSetting(
                newGroupRequest(taskId), withoutExplicitSwitch), CREATOR))
                .isInstanceOf(BusinessException.class).hasMessageContaining("必须开启");

        assertThat(groupSettingMapper.selectByTaskId(taskId)).isNull();
        assertThat(settingMapper.selectByTaskId(taskId)).isNull();
        assertThat(pullTaskMapper.selectLifecycle(taskId).getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void linkModeSubmitLeavesCreationModeAtItsColumnDefault() {
        long taskId = seedDraftWithTwoRows(CREATOR);

        service.create(validRequest(taskId), CREATOR);

        assertThat(pullTaskMapper.selectLifecycle(taskId).getCreationMode())
                .isEqualTo(PullTaskCreationMode.PASTED_LINK);
        PullTaskStandardSetting setting = settingMapper.selectByTaskId(taskId);
        assertThat(setting.getCreatorGroupId()).isNull();
        assertThat(setting.getInitialStationCount()).isZero();
        assertThat(setting.getPullIntervalSeconds()).isEqualTo(30);
        assertThat(setting.getPullIntervalMaxSeconds()).isEqualTo(30);
    }

    @Test
    void newGroupModeWithoutCreatorGroupIsRejected() {
        long taskId = seedDraftWithTwoRows(CREATOR);
        PullTaskStandardCreateDTO request = newGroupRequest(taskId);
        PullTaskStandardCreateDTO withoutCreator = new PullTaskStandardCreateDTO(
                request.draftTaskId(), request.taskName(), request.remark(), request.autoStart(),
                request.groupFolderId(), request.pullerSyncMode(), request.materialAdminTiming(),
                request.clearExistingMembers(), request.pullerJoinByLink(),
                request.earlyPullCount(), request.earlyPullCallCount(), request.pullCountMin(),
                request.pullCountMax(), request.pullIntervalSeconds(),
                request.pullerCountPerGroup(), request.stationCountPerCall(),
                request.concurrentGroupCount(), request.managerGroupId(), request.pullerGroupId(),
                request.stationGroupId(), request.managerFinishGroupId(),
                request.pullerFinishGroupId(), request.groupSetting(), request.creationMode(),
                null, request.initialStationCount(), request.creatorLeaveAfterPull(), request.pullIntervalMaxSeconds());

        assertThatThrownBy(() -> service.create(withoutCreator, CREATOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("建群人");

        // 整单回滚：任务仍是草稿，配置一行没落。
        assertThat(pullTaskMapper.selectLifecycle(taskId).getStatus()).isEqualTo("DRAFT");
        assertThat(settingMapper.selectByTaskId(taskId)).isNull();
    }

    @Test
    void rejectsPullCountRangeWithMinGreaterThanMax() {
        long taskId = seedDraftWithTwoRows(CREATOR);

        assertThatThrownBy(() -> service.create(
                withPullCount(validRequest(taskId), 9, 3), CREATOR))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsNonPositiveEarlyPullConfiguration() {
        long taskId = seedDraftWithTwoRows(CREATOR);

        assertThatThrownBy(() -> service.create(
                withEarlyPull(validRequest(taskId), 0, 2), CREATOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("前期拉人");
    }

    @Test
    void rejectsGroupThatDoesNotBelongToTenant() {
        long taskId = seedDraftWithTwoRows(CREATOR);
        when(accountGroupService.requireExisting(999L))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "账号分组不存在"));

        assertThatThrownBy(() -> service.create(
                withManagerGroup(validRequest(taskId), 999L), CREATOR))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void zeroStationCountAllowsNoStationGroup() {
        long taskId = seedDraftWithTwoRows(CREATOR);

        service.create(withStation(validRequest(taskId), 0, null), CREATOR);

        assertThat(settingMapper.selectByTaskId(taskId).getStationGroupId()).isNull();
        assertThat(settingMapper.selectByTaskId(taskId).getStationGroupName()).isNull();
    }

    @Test
    void positiveStationCountRejectsMissingStationGroup() {
        long taskId = seedDraftWithTwoRows(CREATOR);

        assertThatThrownBy(() -> service.create(
                withStation(validRequest(taskId), 1, null), CREATOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("站台");
        assertThat(pullTaskMapper.selectLifecycle(taskId).getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void materialFilenameNamingStoresNoManualGroupName() {
        long taskId = seedDraftWithTwoRows(CREATOR);
        PullTaskStandardGroupSettingDTO groupSetting = new PullTaskStandardGroupSettingDTO(
                true, PullTaskGroupSettingTiming.AFTER_PULL, "不应保存", true, null, null,
                false, false, PullTaskEditPermissionMode.UNCHANGED,
                PullTaskMuteMode.UNCHANGED, PullTaskLinkPermissionMode.ADMIN_ONLY,
                PullTaskDisappearingMessageMode.UNCHANGED);

        service.create(withGroupSetting(validRequest(taskId), groupSetting), CREATOR);

        assertThat(groupSettingMapper.selectByTaskId(taskId).getGroupName()).isNull();
    }

    @Test
    void missingAvatarIsRejectedBeforeAnyTaskStateBecomesVisible() {
        long taskId = seedDraftWithTwoRows(CREATOR);
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.NOT_FOUND, "群头像不存在"))
                .when(avatarService).reserveForBinding(7L, "missing.png");

        assertThatThrownBy(() -> service.create(
                withAvatar(validRequest(taskId), "missing.png"), CREATOR))
                .isInstanceOf(BusinessException.class);

        assertThat(pullTaskMapper.selectLifecycle(taskId).getStatus()).isEqualTo("DRAFT");
        assertThat(settingMapper.selectByTaskId(taskId)).isNull();
        assertThat(groupSettingMapper.selectByTaskId(taskId)).isNull();
    }

    @Test
    void failureAfterBothSettingInsertsRollsBackTheWholeSubmission() {
        long taskId = seedDraftWithTwoRows(CREATOR);
        registryFailure.set(true);

        assertThatThrownBy(() -> service.create(validRequest(taskId), CREATOR))
                .isInstanceOf(IllegalStateException.class);

        assertThat(pullTaskMapper.selectLifecycle(taskId).getStatus()).isEqualTo("DRAFT");
        assertThat(settingMapper.selectByTaskId(taskId)).isNull();
        assertThat(groupSettingMapper.selectByTaskId(taskId)).isNull();
    }

    @Test
    void normalizedSettingsLeaveLegacyJsonAndGroupNameUntouched() throws SQLException {
        long taskId = seedDraftWithTwoRows(CREATOR);

        service.create(validRequest(taskId), CREATOR);

        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT config_json, group_name FROM pull_task WHERE id = ?")) {
            statement.setLong(1, taskId);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("config_json")).isEqualTo("{}");
                assertThat(result.getString("group_name")).isNull();
            }
        }
    }

    @Test
    void avatarAlreadyBoundToAnotherActiveTaskIsAConflict() {
        long taskId = seedDraftWithTwoRows(CREATOR);
        org.mockito.Mockito.doThrow(
                        new BusinessException(ErrorCode.CONFLICT, "群头像已被任务使用"))
                .when(avatarService).reserveForBinding(7L, "used.png");

        assertThatThrownBy(() -> service.create(
                withAvatar(validRequest(taskId), "used.png"), CREATOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("使用");
        assertThat(pullTaskMapper.selectLifecycle(taskId).getStatus()).isEqualTo("DRAFT");
    }

    /**
     * 造一个带两条执行行的草稿，链接与料子固定为 LINK_A/LINK_B 各一个有效号码。
     *
     * @param creator 创建人用户 ID
     * @return 草稿任务 ID
     */
    private long seedDraftWithTwoRows(long creator) {
        long taskId = writer.ensureDraft(creator, OPERATOR, 100L).getId();
        writer.append(taskId, List.of(
                appendRow(1, LINK_A, "a.txt", 1, "8613800138001"),
                appendRow(2, LINK_B, "b.txt", 2, "8613800138002")), 200L);
        return taskId;
    }

    private long seedNewGroupDraft(long creator) {
        long taskId = writer.ensureDraft(creator, OPERATOR, 100L).getId();
        writer.append(taskId, List.of(
                newGroupAppendRow(1, "a.txt", "8613800138001"),
                newGroupAppendRow(2, "b.txt", "8613800138002")), 200L);
        return taskId;
    }

    private long seedResourcePoolDraft(long creator) {
        long taskId = writer.ensureDraft(creator, OPERATOR, 100L).getId();
        writer.append(taskId, List.of(
                resourcePoolAppendRow(1, "a.txt", "8613800138001"),
                resourcePoolAppendRow(2, "b.txt", "8613800138002")), 200L);
        return taskId;
    }

    private static AppendRow resourcePoolAppendRow(int seq, String fileName, String phone) {
        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setSeq(seq);
        execution.setStage(PullTaskExecutionStage.MANAGER_JOIN.code());
        execution.setSourceFileIndex(seq);
        execution.setSourceFileName(fileName);
        execution.setTotalLineCount(1);
        execution.setValidMemberCount(1);
        execution.setInvalidLineCount(0);
        execution.setDuplicateLineCount(0);

        PullTaskMaterialMember member = new PullTaskMaterialMember();
        member.setMemberSeq(1);
        member.setSourceLineNo(1);
        member.setNormalizedPhone(phone);
        member.setAdminRequired(0);
        return new AppendRow(execution, List.of(member));
    }

    private static AppendRow newGroupAppendRow(int seq, String fileName, String phone) {
        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setSeq(seq);
        execution.setStage(PullTaskExecutionStage.GROUP_CREATE.code());
        execution.setSourceFileIndex(seq);
        execution.setSourceFileName(fileName);
        execution.setTotalLineCount(1);
        execution.setValidMemberCount(1);
        execution.setInvalidLineCount(0);
        execution.setDuplicateLineCount(0);

        PullTaskMaterialMember member = new PullTaskMaterialMember();
        member.setMemberSeq(1);
        member.setSourceLineNo(1);
        member.setNormalizedPhone(phone);
        member.setAdminRequired(0);
        return new AppendRow(execution, List.of(member));
    }

    private static AppendRow appendRow(int seq, String link, String fileName,
                                       int fileIndex, String phone) {
        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setSeq(seq);
        execution.setNormalizedLink(link);
        execution.setInviteCode(link.substring(link.lastIndexOf('/') + 1));
        execution.setSourceLinkLineNo(seq);
        execution.setSourceFileIndex(fileIndex);
        execution.setSourceFileName(fileName);
        execution.setTotalLineCount(1);
        execution.setValidMemberCount(1);
        execution.setInvalidLineCount(0);
        execution.setDuplicateLineCount(0);

        PullTaskMaterialMember member = new PullTaskMaterialMember();
        member.setMemberSeq(1);
        member.setSourceLineNo(1);
        member.setNormalizedPhone(phone);
        member.setAdminRequired(0);
        return new AppendRow(execution, List.of(member));
    }

    /**
     * 填满合法值的提交入参。
     *
     * @param taskId 草稿任务 ID
     * @return 合法入参
     */
    private static PullTaskStandardCreateDTO validRequest(long taskId) {
        return new PullTaskStandardCreateDTO(
                taskId, "任务", null, 0, null, PullTaskPullerSyncMode.SINGLE,
                1, false, false, 1, 2, 3, 8, 30, 2, 2, 1,
                11L, 12L, 13L, null, null, validGroupSetting(),
                null, null, null, false, null);
    }

    private static PullTaskStandardCreateDTO resourcePoolRequest(
            long taskId, Long groupFolderId) {
        return new PullTaskStandardCreateDTO(
                taskId, "资源池任务", null, 0, groupFolderId,
                PullTaskPullerSyncMode.SINGLE, 1, false, false,
                1, 2, 3, 8, 30, 2, 2, 1,
                11L, 12L, 13L, null, null, validGroupSetting(),
                PullTaskCreationMode.RESOURCE_POOL, null, null, false, null);
    }

    private static PullTaskStandardGroupSettingDTO validGroupSetting() {
        return new PullTaskStandardGroupSettingDTO(
                true, PullTaskGroupSettingTiming.AFTER_PULL, "客户群", false, null, null,
                false, false, PullTaskEditPermissionMode.UNCHANGED,
                PullTaskMuteMode.UNCHANGED, PullTaskLinkPermissionMode.ADMIN_ONLY,
                PullTaskDisappearingMessageMode.UNCHANGED);
    }

    /**
     * 新群模式入参：建群人分组 16，建群时带 2 个初始站台。
     *
     * @param taskId 草稿任务 ID
     * @return 新群模式整单提交入参
     */
    private static PullTaskStandardCreateDTO newGroupRequest(long taskId) {
        PullTaskStandardGroupSettingDTO profile = new PullTaskStandardGroupSettingDTO(
                true, PullTaskGroupSettingTiming.BEFORE_PULL, "客户群", false, null, "完整群简介\n第二行",
                false, false, PullTaskEditPermissionMode.UNCHANGED,
                PullTaskMuteMode.UNCHANGED, PullTaskLinkPermissionMode.ADMIN_ONLY,
                PullTaskDisappearingMessageMode.UNCHANGED);
        return new PullTaskStandardCreateDTO(
                taskId, "任务", null, 0, null, PullTaskPullerSyncMode.SINGLE,
                1, false, false, 1, 0, 1, 3, 10, 2, 2, 1,
                11L, 12L, 13L, null, null, profile,
                PullTaskCreationMode.NEW_GROUP, 16L, 2, true, 15);
    }

    /**
     * 派生入参：替换拉人料子人数区间。
     *
     * @param base 基础入参
     * @param min  下限
     * @param max  上限
     * @return 替换区间后的入参
     */
    private static PullTaskStandardCreateDTO withPullCount(PullTaskStandardCreateDTO base,
                                                           int min, int max) {
        return new PullTaskStandardCreateDTO(base.draftTaskId(), base.taskName(),
                base.remark(), base.autoStart(), base.groupFolderId(), base.pullerSyncMode(),
                base.materialAdminTiming(), base.clearExistingMembers(), base.pullerJoinByLink(),
                base.earlyPullCount(),
                base.earlyPullCallCount(), min, max,
                base.pullIntervalSeconds(), base.pullerCountPerGroup(),
                base.stationCountPerCall(), base.concurrentGroupCount(),
                base.managerGroupId(), base.pullerGroupId(), base.stationGroupId(),
                base.managerFinishGroupId(), base.pullerFinishGroupId(), base.groupSetting(), base.creationMode(), base.creatorGroupId(),
                base.initialStationCount(), base.creatorLeaveAfterPull(), base.pullIntervalMaxSeconds());
    }

    private static PullTaskStandardCreateDTO withEarlyPull(
            PullTaskStandardCreateDTO base, int earlyPullCount, int earlyPullCallCount) {
        return new PullTaskStandardCreateDTO(base.draftTaskId(), base.taskName(),
                base.remark(), base.autoStart(), base.groupFolderId(), base.pullerSyncMode(),
                base.materialAdminTiming(), base.clearExistingMembers(), base.pullerJoinByLink(),
                earlyPullCount,
                earlyPullCallCount, base.pullCountMin(), base.pullCountMax(),
                base.pullIntervalSeconds(), base.pullerCountPerGroup(),
                base.stationCountPerCall(), base.concurrentGroupCount(),
                base.managerGroupId(), base.pullerGroupId(), base.stationGroupId(),
                base.managerFinishGroupId(), base.pullerFinishGroupId(), base.groupSetting(), base.creationMode(), base.creatorGroupId(),
                base.initialStationCount(), base.creatorLeaveAfterPull(), base.pullIntervalMaxSeconds());
    }

    /**
     * 派生入参：替换管理账号分组 ID。
     *
     * @param base           基础入参
     * @param managerGroupId 替换后的管理账号分组 ID
     * @return 替换分组后的入参
     */
    private static PullTaskStandardCreateDTO withManagerGroup(PullTaskStandardCreateDTO base,
                                                              Long managerGroupId) {
        return new PullTaskStandardCreateDTO(base.draftTaskId(), base.taskName(),
                base.remark(), base.autoStart(), base.groupFolderId(), base.pullerSyncMode(),
                base.materialAdminTiming(), base.clearExistingMembers(), base.pullerJoinByLink(),
                base.earlyPullCount(),
                base.earlyPullCallCount(), base.pullCountMin(),
                base.pullCountMax(), base.pullIntervalSeconds(), base.pullerCountPerGroup(),
                base.stationCountPerCall(), base.concurrentGroupCount(),
                managerGroupId, base.pullerGroupId(), base.stationGroupId(),
                base.managerFinishGroupId(), base.pullerFinishGroupId(), base.groupSetting(), base.creationMode(), base.creatorGroupId(),
                base.initialStationCount(), base.creatorLeaveAfterPull(), base.pullIntervalMaxSeconds());
    }

    private static PullTaskStandardCreateDTO withAutoStart(PullTaskStandardCreateDTO base,
                                                            int autoStart) {
        return new PullTaskStandardCreateDTO(base.draftTaskId(), base.taskName(),
                base.remark(), autoStart, base.groupFolderId(), base.pullerSyncMode(),
                base.materialAdminTiming(), base.clearExistingMembers(), base.pullerJoinByLink(),
                base.earlyPullCount(),
                base.earlyPullCallCount(), base.pullCountMin(),
                base.pullCountMax(), base.pullIntervalSeconds(), base.pullerCountPerGroup(),
                base.stationCountPerCall(), base.concurrentGroupCount(),
                base.managerGroupId(), base.pullerGroupId(), base.stationGroupId(),
                base.managerFinishGroupId(), base.pullerFinishGroupId(), base.groupSetting(), base.creationMode(), base.creatorGroupId(),
                base.initialStationCount(), base.creatorLeaveAfterPull(), base.pullIntervalMaxSeconds());
    }

    private static PullTaskStandardCreateDTO withStation(
            PullTaskStandardCreateDTO base, int stationCount, Long stationGroupId) {
        return new PullTaskStandardCreateDTO(
                base.draftTaskId(), base.taskName(), base.remark(),
                base.autoStart(), base.groupFolderId(), base.pullerSyncMode(),
                base.materialAdminTiming(), base.clearExistingMembers(), base.pullerJoinByLink(),
                base.earlyPullCount(),
                base.earlyPullCallCount(), base.pullCountMin(),
                base.pullCountMax(), base.pullIntervalSeconds(), base.pullerCountPerGroup(),
                stationCount, base.concurrentGroupCount(),
                base.managerGroupId(), base.pullerGroupId(), stationGroupId,
                base.managerFinishGroupId(), base.pullerFinishGroupId(), base.groupSetting(), base.creationMode(), base.creatorGroupId(),
                base.initialStationCount(), base.creatorLeaveAfterPull(), base.pullIntervalMaxSeconds());
    }

    private static PullTaskStandardCreateDTO withGroupName(
            PullTaskStandardCreateDTO base, String groupName) {
        PullTaskStandardGroupSettingDTO setting = base.groupSetting();
        return withGroupSetting(base, new PullTaskStandardGroupSettingDTO(
                setting.enabled(), setting.settingTiming(), groupName,
                setting.useMaterialFileNameAsGroupName(), setting.avatarFileKey(), setting.groupDescription(),
                setting.autoCloseMuteAfterTask(), setting.autoCloseInviteAfterTask(), setting.editPermission(),
                setting.muteMode(), setting.linkPermission(), setting.disappearingMessage()));
    }

    private static PullTaskStandardCreateDTO withGroupSetting(
            PullTaskStandardCreateDTO base, PullTaskStandardGroupSettingDTO groupSetting) {
        return new PullTaskStandardCreateDTO(
                base.draftTaskId(), base.taskName(), base.remark(),
                base.autoStart(), base.groupFolderId(), base.pullerSyncMode(),
                base.materialAdminTiming(), base.clearExistingMembers(), base.pullerJoinByLink(),
                base.earlyPullCount(),
                base.earlyPullCallCount(), base.pullCountMin(),
                base.pullCountMax(), base.pullIntervalSeconds(), base.pullerCountPerGroup(),
                base.stationCountPerCall(), base.concurrentGroupCount(),
                base.managerGroupId(), base.pullerGroupId(), base.stationGroupId(),
                base.managerFinishGroupId(), base.pullerFinishGroupId(), groupSetting,
                base.creationMode(), base.creatorGroupId(), base.initialStationCount(),
                base.creatorLeaveAfterPull(), base.pullIntervalMaxSeconds());
    }

    private static PullTaskStandardCreateDTO withAvatar(
            PullTaskStandardCreateDTO base, String avatarFileKey) {
        PullTaskStandardGroupSettingDTO current = base.groupSetting();
        PullTaskStandardGroupSettingDTO groupSetting = new PullTaskStandardGroupSettingDTO(
                true, current.settingTiming(), current.groupName(),
                current.useMaterialFileNameAsGroupName(), avatarFileKey,
                current.groupDescription(), current.autoCloseMuteAfterTask(),
                current.autoCloseInviteAfterTask(), current.editPermission(), current.muteMode(),
                current.linkPermission(), current.disappearingMessage());
        return withGroupSetting(base, groupSetting);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_create_test");
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
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskMaterialMemberMapper.xml",
                    "mapper/task/PullTaskStandardSettingMapper.xml",
                    "mapper/task/PullTaskStandardGroupSettingMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskMapper pullTaskMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMapper.class);
        }

        @Bean
        PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }

        @Bean
        PullTaskMaterialMemberMapper materialMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMaterialMemberMapper.class);
        }

        @Bean
        PullTaskStandardSettingMapper settingMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskStandardSettingMapper.class);
        }

        @Bean
        PullTaskStandardGroupSettingMapper groupSettingMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskStandardGroupSettingMapper.class);
        }

        @Bean
        PullTaskGroupAvatarService avatarService() {
            return mock(PullTaskGroupAvatarService.class);
        }

        @Bean
        PullTaskLinkProbeService probeService() {
            return new PullTaskLinkProbeService();
        }

        @Bean
        PullTaskMaterialTxtParser txtParser() {
            return new PullTaskMaterialTxtParser();
        }

        @Bean
        PullTaskStandardDraftWriter writer(PullTaskMapper pullTaskMapper,
                                           PullTaskGroupExecutionMapper executionMapper,
                                           PullTaskMaterialMemberMapper materialMapper) {
            return new PullTaskStandardDraftWriter(pullTaskMapper, executionMapper, materialMapper);
        }

        @Bean
        PullTaskStandardDraftService draftService(PullTaskMapper pullTaskMapper,
                                                  PullTaskGroupExecutionMapper executionMapper,
                                                  PullTaskStandardDraftWriter writer,
                                                  PullTaskMaterialTxtParser txtParser,
                                                  PullTaskLinkProbeService probeService,
                                                  GroupFolderService groupFolderService) {
            return new PullTaskStandardDraftServiceImpl(
                    pullTaskMapper, executionMapper, writer, txtParser,
                    new com.armada.task.service.impl.PullTaskStandardDraftSources( probeService,
                    groupFolderService,
                    mock(com.armada.task.service.impl.PullTaskDataPackageSourceService.class)));
        }

        @Bean
        GroupFolderService groupFolderService() {
            GroupFolderService mock = mock(GroupFolderService.class);
            when(mock.requireExisting(anyLong()))
                    .thenReturn(new GroupFolderOptionVO(18L, "默认群分组"));
            return mock;
        }

        @Bean
        AccountGroupService accountGroupService() {
            AccountGroupService mock = mock(AccountGroupService.class);
            AccountGroup group = new AccountGroup();
            group.setName("默认分组");
            when(mock.requireExisting(anyLong())).thenReturn(group);
            return mock;
        }

        @Bean
        AccountProtocolLookupService accountProtocolLookupService() {
            AccountProtocolLookupService mock = mock(AccountProtocolLookupService.class);
            when(mock.findOnlinePullTaskAccountsStrictByGroupId(anyLong())).thenReturn(List.of(
                    protocolRef(101L), protocolRef(102L), protocolRef(103L)));
            return mock;
        }

        @Bean
        GroupLinkRegistryService groupLinkRegistryService(AtomicBoolean registryFailure) {
            GroupLinkRegistryService mock = mock(GroupLinkRegistryService.class);
            AtomicLong sequence = new AtomicLong(1000);
            when(mock.registerPullTaskTargets(anyList(), anyLong())).thenAnswer(invocation -> {
                if (registryFailure.get()) {
                    throw new IllegalStateException("injected registry failure");
                }
                List<String> links = invocation.getArgument(0);
                Map<String, Long> result = new LinkedHashMap<>();
                for (String link : links) {
                    result.put(link, sequence.incrementAndGet());
                }
                return result;
            });
            return mock;
        }

        @Bean
        AtomicBoolean registryFailure() {
            return new AtomicBoolean(false);
        }

        @Bean
        PullTaskStandardSettingWriter settingWriter(PullTaskStandardSettingMapper settingMapper,
                                                     AccountGroupService accountGroupService,
                                                     GroupFolderService groupFolderService,
                                                     AccountProtocolLookupService accountLookup) {
            return new PullTaskStandardSettingWriter(
                    settingMapper, accountGroupService, groupFolderService, accountLookup);
        }

        @Bean
        PullTaskStandardGroupSettingWriter groupSettingWriter(
                PullTaskStandardGroupSettingMapper mapper) {
            return new PullTaskStandardGroupSettingWriter(mapper);
        }

        @Bean
        PullTaskExecutionDispatchTrigger dispatchTrigger() {
            return mock(PullTaskExecutionDispatchTrigger.class);
        }

        @Bean
        PullTaskStandardStartService startService(PullTaskMapper pullTaskMapper,
                                                  PullTaskStandardSettingMapper settingMapper,
                                                  PullTaskExecutionDispatchTrigger trigger) {
            return new PullTaskStandardStartServiceImpl(
                    pullTaskMapper, settingMapper, trigger, () -> 900L);
        }

        @Bean
        PullTaskStandardCreateTransactionService transactionService(
                PullTaskMapper pullTaskMapper,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskStandardSettingWriter settingWriter,
                PullTaskStandardGroupSettingWriter groupSettingWriter,
                PullTaskGroupAvatarService avatarService,
                GroupLinkRegistryService groupLinkRegistryService) {
            return new PullTaskStandardCreateTransactionService(
                    pullTaskMapper, executionMapper,
                    new com.armada.task.service.impl.PullTaskStandardCreateResources( settingWriter, groupSettingWriter,
                    avatarService, groupLinkRegistryService,
                    mock(com.armada.task.service.impl.PullTaskDataPackageSourceService.class)));
        }

        @Bean
        PullTaskStandardCreateService createService(
                PullTaskStandardCreateTransactionService transactionService,
                PullTaskMapper pullTaskMapper,
                PullTaskStandardSettingMapper settingMapper,
                PullTaskStandardStartService startService) {
            return new PullTaskStandardCreateServiceImpl(
                    transactionService, pullTaskMapper, settingMapper, startService);
        }
    }

    private static ProtocolAccountRef protocolRef(long accountId) {
        String phone = "8613800" + accountId;
        return new ProtocolAccountRef(accountId, ProtocolBackend.WEB, phone, phone);
    }
}
