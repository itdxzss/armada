package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.service.GroupLinkService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskStandardReadMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.model.dto.PullTaskStandardExecutionFilter;
import com.armada.task.model.dto.PullTaskStandardExecutionQuery;
import com.armada.task.model.dto.PullTaskStandardAggregateCriteria;
import com.armada.task.model.dto.PullTaskStandardExecutionAggregateCriteria;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.entity.PullTaskStandardGroupSetting;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.vo.PullTaskStandardExecutionAggregate;
import com.armada.task.model.vo.PullTaskStandardTaskAggregate;
import com.armada.task.service.impl.PullTaskStandardReadServiceImpl;
import com.armada.task.service.impl.PullTaskStandardReadFactMappers;
import com.armada.task.service.impl.PullTaskStandardReadResources;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PullTaskStandardReadServiceTest {

    private static final DataScope USER_SCOPE = DataScope.self(501L);

    private final PullTaskMapper taskMapper = mock(PullTaskMapper.class);
    private final PullTaskGroupExecutionMapper executionMapper =
            mock(PullTaskGroupExecutionMapper.class);
    private final PullTaskGroupAccountMapper accountMapper = mock(PullTaskGroupAccountMapper.class);
    private final PullTaskMaterialMemberMapper materialMapper =
            mock(PullTaskMaterialMemberMapper.class);
    private final PullTaskPullCallMapper callMapper = mock(PullTaskPullCallMapper.class);
    private final PullTaskAccountActionMapper actionMapper = mock(PullTaskAccountActionMapper.class);
    private final PullTaskStandardReadMapper readMapper = mock(PullTaskStandardReadMapper.class);
    private final PullTaskStandardSettingMapper settingMapper =
            mock(PullTaskStandardSettingMapper.class);
    private final PullTaskStandardGroupSettingMapper groupSettingMapper =
            mock(PullTaskStandardGroupSettingMapper.class);
    private final GroupLinkService groupLinkService = mock(GroupLinkService.class);
    private final PullTaskStandardReadService service = new PullTaskStandardReadServiceImpl(
            taskMapper,
            new PullTaskStandardReadResources(
                    executionMapper,
                    readMapper,
                    settingMapper,
                    groupSettingMapper,
                    new PullTaskStandardReadFactMappers(
                            accountMapper, materialMapper, callMapper, actionMapper)),
            groupLinkService);

    @BeforeEach
    void openDataScope() {
        DataScopeContext.open(USER_SCOPE);
    }

    @AfterEach
    void clearDataScope() {
        DataScopeContext.clear();
    }

    @Test
    void readsTaskExecutionCallsRolesAndMemberFactsWithoutStaticSamples() {
        PullTask task = task();
        PullTaskGroupExecution execution = execution(11L);
        execution.setSourceFileName("印度料子包.txt");
        when(taskMapper.selectLifecycleForScope(100L, USER_SCOPE)).thenReturn(task);
        when(executionMapper.selectById(11L)).thenReturn(execution);
        PullTaskGroupAccount manager = account(501L, PullTaskGroupAccountRole.MANAGER);
        manager.setAdminStatus(3);
        when(accountMapper.selectByExecutionAndRole(
                11L, PullTaskGroupAccountRole.MANAGER.code()))
                .thenReturn(List.of(manager));
        when(accountMapper.selectByExecutionAndRole(
                11L, PullTaskGroupAccountRole.PULLER.code()))
                .thenReturn(List.of(account(502L, PullTaskGroupAccountRole.PULLER)));
        PullTaskGroupAccount station = account(503L, PullTaskGroupAccountRole.STATION);
        station.setMembershipReasonCode("PRIVACY_BLOCKED");
        station.setMembershipReasonMessage("privacy blocked");
        station.setMembershipResultAt(5_000L);
        when(accountMapper.selectByExecutionAndRole(
                11L, PullTaskGroupAccountRole.STATION.code()))
                .thenReturn(List.of(station));
        PullTaskGroupAccount promoter = account(504L, PullTaskGroupAccountRole.PROMOTER);
        promoter.setAdminStatus(3);
        when(accountMapper.selectByExecutionAndRole(
                11L, PullTaskGroupAccountRole.PROMOTER.code()))
                .thenReturn(List.of(promoter));
        when(callMapper.selectByExecution(11L)).thenReturn(List.of(call()));
        when(materialMapper.selectByExecution(11L)).thenReturn(List.of(member()));
        when(readMapper.selectExecutionAggregates(
                PullTaskStandardExecutionAggregateCriteria.fromEnums(List.of(11L))))
                .thenReturn(List.of(executionAggregate()));
        when(readMapper.selectTaskAggregates(
                PullTaskStandardAggregateCriteria.fromEnums(List.of(100L))))
                .thenReturn(List.of(taskAggregate()));
        when(settingMapper.selectByTaskId(100L)).thenReturn(setting());
        when(groupSettingMapper.selectByTaskId(100L)).thenReturn(groupSetting());
        when(actionMapper.selectByExecutionAndStatuses(
                org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(action()));
        when(groupLinkService.findWhatsAppGroupNamesByIds(List.of(21L)))
                .thenReturn(Map.of(21L, "WhatsApp 详情群名"));

        assertThat(service.task(100L).executions()).isEmpty();
        assertThat(service.task(100L).creationMode()).isEqualTo(PullTaskCreationMode.NEW_GROUP);
        assertThat(service.task(100L).summary().successfulMemberCount()).isEqualTo(1);
        assertThat(service.task(100L).standardSetting().pullerSyncMode().name())
                .isEqualTo("BATCH");
        assertThat(service.task(100L).standardSetting().earlyPullCount()).isEqualTo(1);
        assertThat(service.task(100L).standardSetting().earlyPullCallCount()).isEqualTo(2);
        assertThat(service.task(100L).standardSetting().pullerJoinByLink()).isTrue();
        assertThat(service.task(100L).standardSetting().groupFolderName()).isEqualTo("印度群");
        assertThat(service.task(100L).standardSetting().creatorGroupId()).isEqualTo(16L);
        assertThat(service.task(100L).standardSetting().creatorGroupName()).isEqualTo("建群人组");
        assertThat(service.task(100L).standardSetting().initialStationCount()).isEqualTo(2);
        assertThat(service.task(100L).groupSetting().settingTiming().name())
                .isEqualTo("AFTER_PULL");
        assertThat(service.task(100L).groupSetting().avatarPreviewUrl())
                .isEqualTo("/api/pull-tasks/standard/group-avatars/avatar.png");
        verify(executionMapper, never()).selectByTaskId(100L);
        var detail = service.execution(100L, 11L);
        assertThat(detail.execution().groupJid()).isEqualTo("120363000000000000@g.us");
        assertThat(detail.execution().groupName()).isEqualTo("WhatsApp 详情群名");
        assertThat(detail.execution().normalizedLink()).isEqualTo("chat.whatsapp.com/AAAA");
        assertThat(detail.execution().sourceFileName()).isEqualTo("印度料子包.txt");
        assertThat(detail.execution().createStep()).isEqualTo(4);
        assertThat(detail.execution().groupSubject()).isEqualTo("印度料子包");
        assertThat(detail.roles()).hasSize(4)
                .filteredOn(row -> row.roleType() == PullTaskGroupAccountRole.STATION.code())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.accountPhone()).isEqualTo("8613800000503");
                    assertThat(row.membershipReasonCode()).isEqualTo("PRIVACY_BLOCKED");
                    assertThat(row.membershipReasonMessage()).isEqualTo("privacy blocked");
                    assertThat(row.membershipResultAt()).isEqualTo(5_000L);
                });
        assertThat(detail.roles())
                .filteredOn(row -> row.roleType() == PullTaskGroupAccountRole.PROMOTER.code())
                .singleElement()
                .satisfies(row -> assertThat(row.adminStatus()).isEqualTo(3));
        assertThat(detail.calls()).singleElement()
                .satisfies(row -> {
                    assertThat(row.callStatus()).isEqualTo(3);
                    assertThat(row.pullerAccountId()).isNull();
                });
        assertThat(detail.actions()).singleElement()
                .extracting(row -> row.actionStatus()).isEqualTo(3);
        assertThat(service.members(100L, 11L)).singleElement()
                .satisfies(row -> {
                    assertThat(row.normalizedPhone()).isEqualTo("8613900000001");
                    assertThat(row.waJid()).isEqualTo("861******0001@s.whatsapp.net");
                    assertThat(row.pullStatus()).isEqualTo(3);
                    assertThat(row.pullReasonCode()).isEqualTo("PRIVACY");
                });
    }

    @Test
    void executionMustBelongToTheRequestedTask() {
        when(taskMapper.selectLifecycleForScope(100L, USER_SCOPE)).thenReturn(task());
        PullTaskGroupExecution otherTask = execution(11L);
        otherTask.setTaskId(101L);
        when(executionMapper.selectById(11L)).thenReturn(otherTask);

        assertThatThrownBy(() -> service.execution(100L, 11L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("执行行不存在");
    }

    @Test
    void submittedDetailsRejectCreatorScopedDraftTasks() {
        PullTask draft = task();
        draft.setStatus("DRAFT");
        when(taskMapper.selectLifecycleForScope(100L, USER_SCOPE)).thenReturn(draft);

        assertThatThrownBy(() -> service.task(100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("草稿");
    }

    @Test
    void executionWorkbenchUsesServerSideFilterAndPagination() {
        PullTaskStandardExecutionQuery query = new PullTaskStandardExecutionQuery();
        query.setPage(2);
        query.setPageSize(1);
        query.setKeyword("  AAAA  ");
        query.setExecutionStatus(2);
        when(taskMapper.selectLifecycleForScope(100L, USER_SCOPE)).thenReturn(task());
        PullTaskStandardExecutionFilter filter = new PullTaskStandardExecutionFilter(
                100L, "AAAA", 2, null, null, null);
        when(readMapper.countExecutions(filter)).thenReturn(2L);
        when(readMapper.selectExecutionPage(filter, 1, 1))
                .thenReturn(List.of(execution(11L)));
        when(readMapper.selectExecutionAggregates(
                PullTaskStandardExecutionAggregateCriteria.fromEnums(List.of(11L))))
                .thenReturn(List.of(executionAggregate()));
        when(groupLinkService.findWhatsAppGroupNamesByIds(List.of(21L)))
                .thenReturn(Map.of(21L, "WhatsApp 列表群名"));

        var result = service.executions(100L, query);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.list()).singleElement()
                .satisfies(row -> {
                    assertThat(row.executionId()).isEqualTo(11L);
                    assertThat(row.groupName()).isEqualTo("WhatsApp 列表群名");
                    assertThat(row.normalizedLink()).isEqualTo("chat.whatsapp.com/AAAA");
                    assertThat(row.sourceFileName()).isNull();
                    assertThat(row.manualPaused()).isTrue();
                    assertThat(row.waitResourceType()).isEqualTo(1);
                    assertThat(row.materialSummary().successfulCount()).isEqualTo(1);
                    assertThat(row.managers().missingCount()).isZero();
                });
    }

    private static PullTask task() {
        PullTask row = new PullTask();
        row.setId(100L);
        row.setTaskType(PullTaskType.STANDARD);
        row.setMode("NORMAL_LINK");
        row.setCreationMode(PullTaskCreationMode.NEW_GROUP);
        row.setTaskName("真实任务");
        row.setStatus("EXECUTING");
        row.setGroupCount(1);
        row.setExpectedPullCount(1);
        return row;
    }

    private static PullTaskGroupExecution execution(long id) {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(id);
        row.setTaskId(100L);
        row.setSeq(1);
        row.setGroupLinkId(21L);
        row.setNormalizedLink("chat.whatsapp.com/AAAA");
        row.setGroupJid("120363000000000000@g.us");
        row.setExecutionStatus(2);
        row.setStage(PullTaskExecutionStage.PULL_EXECUTION.code());
        row.setCreateStep(4);
        row.setGroupSubject("印度料子包");
        row.setManualPaused(1);
        row.setWaitResourceType(1);
        row.setValidMemberCount(1);
        return row;
    }

    private static PullTaskGroupAccount account(long id, PullTaskGroupAccountRole role) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setId(id);
        row.setAccountId(id + 1_000L);
        row.setAccountPhone("8613800000" + id);
        row.setRoleType(role.code());
        row.setRoleSeq(1);
        row.setMembershipStatus(2);
        row.setAvailabilityStatus(1);
        return row;
    }

    private static PullTaskPullCall call() {
        PullTaskPullCall row = new PullTaskPullCall();
        row.setId(801L);
        row.setCallSeq(1);
        row.setPlannedMaterialCount(1);
        row.setPlannedStationCount(1);
        row.setCallStatus(3);
        return row;
    }

    private static PullTaskMaterialMember member() {
        PullTaskMaterialMember row = new PullTaskMaterialMember();
        row.setId(601L);
        row.setMemberSeq(1);
        row.setNormalizedPhone("8613900000001");
        row.setAdminRequired(0);
        row.setPullCallId(801L);
        row.setPullStatus(3);
        row.setPullReasonCode("PRIVACY");
        row.setWaJid("8613900000001@s.whatsapp.net");
        return row;
    }

    private static PullTaskStandardExecutionAggregate executionAggregate() {
        PullTaskStandardExecutionAggregate row = new PullTaskStandardExecutionAggregate();
        row.setExecutionId(11L);
        row.setTotalMemberCount(1);
        row.setSuccessfulMemberCount(1);
        row.setFailedMemberCount(0);
        row.setUnknownMemberCount(0);
        row.setUnconsumedMemberCount(0);
        row.setSubmittedMemberCount(0);
        row.setCanceledMemberCount(0);
        row.setRequiredManagerCount(1);
        row.setPlannedPullerCount(1);
        row.setPlannedStationCount(1);
        row.setCurrentManagerCount(1);
        row.setCurrentPullerCount(1);
        row.setCurrentStationCount(1);
        return row;
    }

    private static PullTaskStandardTaskAggregate taskAggregate() {
        PullTaskStandardTaskAggregate row = new PullTaskStandardTaskAggregate();
        row.setTaskId(100L);
        row.setTotalGroupCount(1);
        row.setExecutingGroupCount(1);
        row.setCompletedGroupCount(0);
        row.setFailedGroupCount(0);
        row.setAbandonedGroupCount(0);
        row.setManagerShortageGroupCount(0);
        row.setPullerShortageGroupCount(0);
        row.setStationShortageGroupCount(0);
        row.setTotalMemberCount(1);
        row.setSuccessfulMemberCount(1);
        row.setFailedMemberCount(0);
        row.setUnknownMemberCount(0);
        row.setUnconsumedMemberCount(0);
        return row;
    }

    private static PullTaskStandardSetting setting() {
        PullTaskStandardSetting row = new PullTaskStandardSetting();
        row.setAutoStart(0);
        row.setSourceGroupFolderId(18L);
        row.setSourceGroupFolderName("印度群");
        row.setPullerSyncMode(2);
        row.setMaterialAdminTiming(1);
        row.setClearExistingMembers(1);
        row.setPullerJoinByLink(1);
        row.setEarlyPullCount(1);
        row.setEarlyPullCallCount(2);
        row.setPullCountMin(3);
        row.setPullCountMax(8);
        row.setPullIntervalSeconds(30);
        row.setPullerCountPerGroup(2);
        row.setStationCountPerCall(0);
        row.setConcurrentGroupCount(1);
        row.setInitialStationCount(2);
        row.setCreatorGroupId(16L);
        row.setCreatorGroupName("建群人组");
        row.setPullerRiskMinutes(10);
        row.setManagerGroupId(11L);
        row.setManagerGroupName("管理组");
        row.setPullerGroupId(12L);
        row.setPullerGroupName("拉手组");
        return row;
    }

    private static PullTaskStandardGroupSetting groupSetting() {
        PullTaskStandardGroupSetting row = new PullTaskStandardGroupSetting();
        row.setSettingTiming(2);
        row.setGroupName("客户群");
        row.setMaterialFilenameAsGroupName(0);
        row.setAvatarFileKey("avatar.png");
        row.setGroupDescription("说明");
        row.setAutoUnmuteAfterTask(1);
        row.setAutoCloseInviteAfterTask(1);
        row.setEditPermissionMode(2);
        row.setMuteMode(1);
        row.setLinkPermissionMode(2);
        row.setDisappearingMessageMode(3);
        return row;
    }

    private static PullTaskAccountAction action() {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setId(701L);
        row.setActionType(1);
        row.setActorGroupAccountId(501L);
        row.setTargetGroupAccountId(502L);
        row.setActionStatus(3);
        return row;
    }
}
