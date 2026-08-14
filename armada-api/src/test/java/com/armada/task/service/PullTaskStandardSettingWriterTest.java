package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.model.entity.AccountGroup;
import com.armada.account.service.AccountGroupService;
import com.armada.group.model.vo.GroupFolderOptionVO;
import com.armada.group.service.GroupFolderService;
import com.armada.shared.exception.BusinessException;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskStandardCreateDTO;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskPullerSyncMode;
import com.armada.task.service.impl.PullTaskStandardSettingWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 普通群链接执行设置写入规则测试。 */
class PullTaskStandardSettingWriterTest {

    private final PullTaskStandardSettingMapper mapper = mock(PullTaskStandardSettingMapper.class);
    private final AccountGroupService accountGroupService = mock(AccountGroupService.class);
    private final GroupFolderService groupFolderService = mock(GroupFolderService.class);
    private final PullTaskStandardSettingWriter writer =
            new PullTaskStandardSettingWriter(mapper, accountGroupService, groupFolderService);

    @BeforeEach
    void setUp() {
        when(accountGroupService.requireExisting(11L)).thenReturn(group("管理组"));
        when(accountGroupService.requireExisting(12L)).thenReturn(group("拉手组"));
        when(accountGroupService.requireExisting(14L)).thenReturn(group("管理完成组"));
        when(accountGroupService.requireExisting(15L)).thenReturn(group("拉手完成组"));
        when(groupFolderService.requireExisting(18L))
                .thenReturn(new GroupFolderOptionVO(18L, "印度群"));
    }

    @Test
    void snapshotsFolderAndAccountGroupNamesFromDomainServices() {
        PullTaskStandardCreateDTO request = request(0, null);

        writer.insert(request, 9L);

        ArgumentCaptor<PullTaskStandardSetting> captor =
                ArgumentCaptor.forClass(PullTaskStandardSetting.class);
        verify(mapper).insert(captor.capture());
        PullTaskStandardSetting saved = captor.getValue();
        assertThat(saved.getSourceGroupFolderId()).isEqualTo(18L);
        assertThat(saved.getSourceGroupFolderName()).isEqualTo("印度群");
        assertThat(saved.getPullerSyncMode()).isEqualTo(2);
        assertThat(saved.getClearExistingMembers()).isEqualTo(1);
        assertThat(saved.getPullerJoinByLink()).isEqualTo(1);
        assertThat(saved.getEarlyPullCount()).isEqualTo(1);
        assertThat(saved.getEarlyPullCallCount()).isEqualTo(2);
        assertThat(saved.getManagerGroupName()).isEqualTo("管理组");
        assertThat(saved.getPullerGroupName()).isEqualTo("拉手组");
        assertThat(saved.getStationGroupId()).isNull();
        assertThat(saved.getStationGroupName()).isNull();
        assertThat(saved.getManagerFinishGroupName()).isEqualTo("管理完成组");
        assertThat(saved.getPullerFinishGroupName()).isEqualTo("拉手完成组");
        verify(accountGroupService, never()).requireExisting(null);
    }

    @Test
    void positiveStationCountRequiresAStationGroup() {
        assertThatThrownBy(() -> writer.insert(request(1, null), 9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("站台");
        verify(mapper, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void omittedLinkJoinSettingKeepsLegacyManagerInviteBehavior() {
        PullTaskStandardCreateDTO request = request(0, null);
        when(request.pullerJoinByLink()).thenReturn(null);

        writer.insert(request, 9L);

        ArgumentCaptor<PullTaskStandardSetting> captor =
                ArgumentCaptor.forClass(PullTaskStandardSetting.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getPullerJoinByLink()).isZero();
    }

    private PullTaskStandardCreateDTO request(int stationCount, Long stationGroupId) {
        PullTaskStandardCreateDTO request = mock(PullTaskStandardCreateDTO.class);
        when(request.autoStart()).thenReturn(0);
        when(request.groupFolderId()).thenReturn(18L);
        when(request.pullerSyncMode()).thenReturn(PullTaskPullerSyncMode.BATCH);
        when(request.materialAdminTiming()).thenReturn(1);
        when(request.clearExistingMembers()).thenReturn(true);
        when(request.pullerJoinByLink()).thenReturn(true);
        when(request.earlyPullCount()).thenReturn(1);
        when(request.earlyPullCallCount()).thenReturn(2);
        when(request.pullCountMin()).thenReturn(3);
        when(request.pullCountMax()).thenReturn(8);
        when(request.pullIntervalSeconds()).thenReturn(30);
        when(request.pullerCountPerGroup()).thenReturn(2);
        when(request.stationCountPerCall()).thenReturn(stationCount);
        when(request.concurrentGroupCount()).thenReturn(1);
        when(request.managerGroupId()).thenReturn(11L);
        when(request.pullerGroupId()).thenReturn(12L);
        when(request.stationGroupId()).thenReturn(stationGroupId);
        when(request.managerFinishGroupId()).thenReturn(14L);
        when(request.pullerFinishGroupId()).thenReturn(15L);
        return request;
    }

    private static AccountGroup group(String name) {
        AccountGroup group = new AccountGroup();
        group.setName(name);
        return group;
    }
}
