package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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

/**
 * 群信息设置总开关对落库的控制。
 *
 * <p>业务口径（2026-08-18 确认）：关闭即整块不走——不保存任何配置，也不做任何校验。
 * 关闭态下前端不展示这些控件，所以请求里带不带值都不影响结果；测试特意把值传进来，
 * 是为了证明后端确实主动忽略了它们，而不是"因为没传所以没存"。</p>
 */
class PullTaskStandardGroupSettingToggleTest {

    private final PullTaskStandardGroupSettingMapper mapper =
            mock(PullTaskStandardGroupSettingMapper.class);
    private final PullTaskStandardGroupSettingWriter writer =
            new PullTaskStandardGroupSettingWriter(mapper);

    /** 开关缺省即关闭：新建表单不带该字段时不能落成启用。 */
    @Test
    void omittedToggleFallsBackToDisabled() {
        writer.insert(fullSetting(null), 9L);

        assertThat(saved().getGroupSettingEnabled()).isZero();
    }

    /** 关闭时整块不保存：12 个字段全部落表默认值，用户传了什么都不算数。 */
    @Test
    void disabledToggleStoresDefaultsAndDiscardsEveryField() {
        writer.insert(fullSetting(false), 9L);

        PullTaskStandardGroupSetting row = saved();
        assertThat(row.getGroupSettingEnabled()).isZero();
        assertThat(row.getSettingTiming()).isEqualTo(2);
        assertThat(row.getGroupName()).isNull();
        assertThat(row.getMaterialFilenameAsGroupName()).isZero();
        assertThat(row.getAvatarFileKey()).isNull();
        assertThat(row.getGroupDescription()).isNull();
        assertThat(row.getAutoUnmuteAfterTask()).isZero();
        assertThat(row.getAutoCloseInviteAfterTask()).isZero();
        assertThat(row.getEditPermissionMode()).isZero();
        assertThat(row.getMuteMode()).isZero();
        assertThat(row.getLinkPermissionMode()).isEqualTo(2);
        assertThat(row.getDisappearingMessageMode()).isZero();
    }

    /** 关闭时不校验：超长文本和缺失选项都不该拦住保存。 */
    @Test
    void disabledToggleSkipsRequiredAndLengthValidation() {
        PullTaskStandardGroupSettingDTO overlong = new PullTaskStandardGroupSettingDTO(
                false, null, "x".repeat(200), null, "y".repeat(600), "z".repeat(2000),
                null, null, null, null, null, null);

        assertThatCode(() -> writer.insert(overlong, 9L)).doesNotThrowAnyException();
    }

    /** 开启时行为不变：字段原样落库，原有校验一条不少。 */
    @Test
    void enabledTogglePersistsEveryFieldAndKeepsValidation() {
        writer.insert(fullSetting(true), 9L);

        PullTaskStandardGroupSetting row = saved();
        assertThat(row.getGroupSettingEnabled()).isEqualTo(1);
        assertThat(row.getGroupName()).isEqualTo("客户群");
        assertThat(row.getGroupDescription()).isEqualTo("群说明");
        assertThat(row.getAvatarFileKey()).isEqualTo("avatar.png");
        assertThat(row.getMuteMode()).isEqualTo(1);

        PullTaskStandardGroupSettingDTO overlong = new PullTaskStandardGroupSettingDTO(
                true, PullTaskGroupSettingTiming.AFTER_PULL, "x".repeat(129), false, null, null,
                false, false, PullTaskEditPermissionMode.UNCHANGED, PullTaskMuteMode.UNCHANGED,
                PullTaskLinkPermissionMode.ADMIN_ONLY, PullTaskDisappearingMessageMode.UNCHANGED);
        assertThatThrownBy(() -> writer.insert(overlong, 9L))
                .isInstanceOf(BusinessException.class);
    }

    private PullTaskStandardGroupSetting saved() {
        ArgumentCaptor<PullTaskStandardGroupSetting> captor =
                ArgumentCaptor.forClass(PullTaskStandardGroupSetting.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    /** 一份填满的请求；只有总开关按用例变化，用来证明关闭时其余字段确实被忽略。 */
    private static PullTaskStandardGroupSettingDTO fullSetting(Boolean enabled) {
        return new PullTaskStandardGroupSettingDTO(
                enabled, PullTaskGroupSettingTiming.BEFORE_PULL, "客户群", false,
                "avatar.png", "群说明", true, true,
                PullTaskEditPermissionMode.DISALLOW, PullTaskMuteMode.MUTE,
                PullTaskLinkPermissionMode.ALL, PullTaskDisappearingMessageMode.SEVEN_DAYS);
    }
}
