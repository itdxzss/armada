package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.group.service.GroupLinkService;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.mapper.PullTaskStandardReadMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskStandardGroupSetting;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.service.impl.PullTaskStandardReadFactMappers;
import com.armada.task.service.impl.PullTaskStandardReadResources;
import com.armada.task.service.impl.PullTaskStandardReadServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 任务详情回读「群信息设置」总开关。
 *
 * <p>开关关闭的任务同样有一行配置（只是整块取默认值），因此详情不能因为开关关闭就判定
 * 「拉群任务完整设置不存在」——那会让所有关闭态任务的详情页直接 404。</p>
 */
class PullTaskStandardGroupSettingToggleDetailTest {

    private static final DataScope USER_SCOPE = DataScope.self(501L);

    private final PullTaskMapper taskMapper = mock(PullTaskMapper.class);
    private final PullTaskStandardSettingMapper settingMapper =
            mock(PullTaskStandardSettingMapper.class);
    private final PullTaskStandardGroupSettingMapper groupSettingMapper =
            mock(PullTaskStandardGroupSettingMapper.class);
    private final PullTaskStandardReadService service = new PullTaskStandardReadServiceImpl(
            taskMapper,
            new PullTaskStandardReadResources(
                    mock(PullTaskGroupExecutionMapper.class),
                    mock(PullTaskStandardReadMapper.class),
                    settingMapper,
                    groupSettingMapper,
                    new PullTaskStandardReadFactMappers(
                            mock(PullTaskGroupAccountMapper.class),
                            mock(PullTaskMaterialMemberMapper.class),
                            mock(PullTaskPullCallMapper.class),
                            mock(PullTaskAccountActionMapper.class))),
            mock(GroupLinkService.class));

    @BeforeEach
    void openDataScope() {
        DataScopeContext.open(USER_SCOPE);
    }

    @AfterEach
    void clearDataScope() {
        DataScopeContext.clear();
    }

    /** 开关状态本身要能回读，前端据此决定整块控件是否展开。 */
    @Test
    void taskDetailExposesGroupSettingToggleState() {
        givenTask(1);

        assertThat(service.task(100L).groupSetting().enabled()).isTrue();
    }

    /** 关闭态任务的详情必须照常打开。 */
    @Test
    void disabledToggleTaskDetailDoesNotReportMissingFullSetting() {
        givenTask(0);

        assertThatCode(() -> service.task(100L)).doesNotThrowAnyException();
    }

    private void givenTask(int groupSettingEnabled) {
        when(taskMapper.selectLifecycleForScope(100L, USER_SCOPE)).thenReturn(task());
        when(settingMapper.selectByTaskId(100L)).thenReturn(setting());
        when(groupSettingMapper.selectByTaskId(100L))
                .thenReturn(groupSetting(groupSettingEnabled));
    }

    private static PullTask task() {
        PullTask row = new PullTask();
        row.setId(100L);
        row.setTaskType(PullTaskType.STANDARD);
        row.setMode("NORMAL_LINK");
        row.setTaskName("真实任务");
        row.setStatus("EXECUTING");
        row.setGroupCount(1);
        row.setExpectedPullCount(1);
        return row;
    }

    private static PullTaskStandardSetting setting() {
        PullTaskStandardSetting row = new PullTaskStandardSetting();
        row.setAutoStart(0);
        row.setPullerSyncMode(2);
        row.setMaterialAdminTiming(1);
        row.setClearExistingMembers(0);
        row.setPullerJoinByLink(0);
        row.setPullCountMin(3);
        row.setPullCountMax(8);
        row.setPullIntervalSeconds(30);
        row.setPullerCountPerGroup(2);
        row.setStationCountPerCall(0);
        row.setConcurrentGroupCount(1);
        return row;
    }

    /** 关闭态是整块默认值，开启态才有真实取值；本用例只关心开关列本身。 */
    private static PullTaskStandardGroupSetting groupSetting(int groupSettingEnabled) {
        PullTaskStandardGroupSetting row = new PullTaskStandardGroupSetting();
        row.setGroupSettingEnabled(groupSettingEnabled);
        row.setSettingTiming(2);
        row.setMaterialFilenameAsGroupName(0);
        row.setAutoUnmuteAfterTask(0);
        row.setAutoCloseInviteAfterTask(0);
        row.setEditPermissionMode(0);
        row.setMuteMode(0);
        row.setLinkPermissionMode(2);
        row.setDisappearingMessageMode(0);
        return row;
    }
}
