package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.armada.shared.exception.BusinessException;
import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.model.dto.PullTaskStandardGroupSettingDTO;
import com.armada.task.model.entity.PullTaskStandardGroupSetting;
import com.armada.task.model.enums.PullTaskDisappearingMessageMode;
import com.armada.task.model.enums.PullTaskEditPermissionMode;
import com.armada.task.model.enums.PullTaskGroupSettingTiming;
import com.armada.task.model.enums.PullTaskLinkPermissionMode;
import com.armada.task.model.enums.PullTaskMuteMode;
import com.armada.task.service.impl.PullTaskStandardGroupSettingWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 普通群链接群资料设置写入规则测试。 */
class PullTaskStandardGroupSettingWriterTest {

    private final PullTaskStandardGroupSettingMapper mapper =
            mock(PullTaskStandardGroupSettingMapper.class);
    private final PullTaskStandardGroupSettingWriter writer =
            new PullTaskStandardGroupSettingWriter(mapper);

    @Test
    void trimsTextAndMapsEveryApiFieldToItsDatabaseColumn() {
        writer.insert(setting(false, "  客户群  ", "  群说明  "), 9L);

        ArgumentCaptor<PullTaskStandardGroupSetting> captor =
                ArgumentCaptor.forClass(PullTaskStandardGroupSetting.class);
        verify(mapper).insert(captor.capture());
        PullTaskStandardGroupSetting saved = captor.getValue();
        assertThat(saved.getTaskId()).isEqualTo(9L);
        assertThat(saved.getSettingTiming()).isEqualTo(2);
        assertThat(saved.getGroupName()).isEqualTo("客户群");
        assertThat(saved.getMaterialFilenameAsGroupName()).isZero();
        assertThat(saved.getAvatarFileKey()).isEqualTo("avatar.png");
        assertThat(saved.getGroupDescription()).isEqualTo("群说明");
        assertThat(saved.getAutoUnmuteAfterTask()).isEqualTo(1);
        assertThat(saved.getAutoCloseInviteAfterTask()).isEqualTo(1);
        assertThat(saved.getEditPermissionMode()).isEqualTo(2);
        assertThat(saved.getMuteMode()).isEqualTo(1);
        assertThat(saved.getLinkPermissionMode()).isEqualTo(2);
        assertThat(saved.getDisappearingMessageMode()).isEqualTo(3);
    }

    @Test
    void materialFilenameNamingForcesStoredManualNameToNull() {
        writer.insert(setting(true, "不应保存", null), 9L);

        ArgumentCaptor<PullTaskStandardGroupSetting> captor =
                ArgumentCaptor.forClass(PullTaskStandardGroupSetting.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getGroupName()).isNull();
        assertThat(captor.getValue().getMaterialFilenameAsGroupName()).isEqualTo(1);
    }

    @Test
    void rejectsMissingSettingOverlongTextAndNullOptions() {
        assertThatThrownBy(() -> writer.insert(null, 9L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> writer.insert(setting(false, "x".repeat(129), null), 9L))
                .isInstanceOf(BusinessException.class);
        PullTaskStandardGroupSettingDTO nullBoolean = new PullTaskStandardGroupSettingDTO(
                PullTaskGroupSettingTiming.AFTER_PULL, null, null, null, null,
                true, true, PullTaskEditPermissionMode.DISALLOW, PullTaskMuteMode.MUTE,
                PullTaskLinkPermissionMode.ADMIN_ONLY,
                PullTaskDisappearingMessageMode.NINETY_DAYS);
        assertThatThrownBy(() -> writer.insert(nullBoolean, 9L))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    private PullTaskStandardGroupSettingDTO setting(
            boolean useFileName, String groupName, String description) {
        return new PullTaskStandardGroupSettingDTO(
                PullTaskGroupSettingTiming.AFTER_PULL, groupName, useFileName,
                "avatar.png", description, true, true,
                PullTaskEditPermissionMode.DISALLOW, PullTaskMuteMode.MUTE,
                PullTaskLinkPermissionMode.ADMIN_ONLY,
                PullTaskDisappearingMessageMode.NINETY_DAYS);
    }
}
