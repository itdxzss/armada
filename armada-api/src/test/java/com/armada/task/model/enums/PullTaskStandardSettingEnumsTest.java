package com.armada.task.model.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 普通群链接大表单枚举与数据库编码合同测试。 */
class PullTaskStandardSettingEnumsTest {

    @Test
    void mapsEveryApiEnumToItsFrozenDatabaseCode() {
        assertThat(PullTaskPullerSyncMode.SINGLE.code()).isEqualTo(1);
        assertThat(PullTaskPullerSyncMode.BATCH.code()).isEqualTo(2);
        assertThat(PullTaskGroupSettingTiming.BEFORE_PULL.code()).isEqualTo(1);
        assertThat(PullTaskGroupSettingTiming.AFTER_PULL.code()).isEqualTo(2);
        assertThat(PullTaskEditPermissionMode.UNCHANGED.code()).isZero();
        assertThat(PullTaskEditPermissionMode.ALLOW.code()).isEqualTo(1);
        assertThat(PullTaskEditPermissionMode.DISALLOW.code()).isEqualTo(2);
        assertThat(PullTaskMuteMode.UNCHANGED.code()).isZero();
        assertThat(PullTaskMuteMode.MUTE.code()).isEqualTo(1);
        assertThat(PullTaskMuteMode.UNMUTE.code()).isEqualTo(2);
        assertThat(PullTaskLinkPermissionMode.ALL.code()).isEqualTo(1);
        assertThat(PullTaskLinkPermissionMode.ADMIN_ONLY.code()).isEqualTo(2);
        assertThat(PullTaskDisappearingMessageMode.UNCHANGED.code()).isZero();
        assertThat(PullTaskDisappearingMessageMode.ONE_DAY.code()).isEqualTo(1);
        assertThat(PullTaskDisappearingMessageMode.SEVEN_DAYS.code()).isEqualTo(2);
        assertThat(PullTaskDisappearingMessageMode.NINETY_DAYS.code()).isEqualTo(3);
        assertThat(PullTaskDisappearingMessageMode.OFF.code()).isEqualTo(4);
    }

    @Test
    void restoresEveryEnumFromItsDatabaseCode() {
        assertThat(PullTaskPullerSyncMode.fromCode(2)).isEqualTo(PullTaskPullerSyncMode.BATCH);
        assertThat(PullTaskGroupSettingTiming.fromCode(1))
                .isEqualTo(PullTaskGroupSettingTiming.BEFORE_PULL);
        assertThat(PullTaskEditPermissionMode.fromCode(2))
                .isEqualTo(PullTaskEditPermissionMode.DISALLOW);
        assertThat(PullTaskMuteMode.fromCode(1)).isEqualTo(PullTaskMuteMode.MUTE);
        assertThat(PullTaskLinkPermissionMode.fromCode(2))
                .isEqualTo(PullTaskLinkPermissionMode.ADMIN_ONLY);
        assertThat(PullTaskDisappearingMessageMode.fromCode(3))
                .isEqualTo(PullTaskDisappearingMessageMode.NINETY_DAYS);
    }

    @Test
    void rejectsUnknownDatabaseCodesExplicitly() {
        assertThatThrownBy(() -> PullTaskPullerSyncMode.fromCode(99))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PullTaskGroupSettingTiming.fromCode(99))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PullTaskEditPermissionMode.fromCode(99))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PullTaskMuteMode.fromCode(99))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PullTaskLinkPermissionMode.fromCode(99))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PullTaskDisappearingMessageMode.fromCode(99))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
