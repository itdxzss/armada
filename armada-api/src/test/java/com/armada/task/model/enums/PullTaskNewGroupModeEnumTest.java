package com.armada.task.model.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 新群模式对两个既有枚举的追加。
 *
 * <p>这两个枚举的取值直接落库，存量行按旧取值解读。因此测试的重点不是「新值存在」，
 * 而是「旧值一个都没变」——改动既有取值会让线上执行行的语义整体漂移。</p>
 */
class PullTaskNewGroupModeEnumTest {

    @Test
    @DisplayName("进群方式追加取值 4，且原有三个取值不变")
    void entryModeAddsGroupCreateInitialWithoutShiftingExistingCodes() {
        assertThat(PullTaskAccountEntryMode.JOIN_BY_LINK.code()).isEqualTo(1);
        assertThat(PullTaskAccountEntryMode.MANAGER_INVITE.code()).isEqualTo(2);
        assertThat(PullTaskAccountEntryMode.PULLER_ADD.code()).isEqualTo(3);
        assertThat(PullTaskAccountEntryMode.GROUP_CREATE_INITIAL.code()).isEqualTo(4);

        assertThat(PullTaskAccountEntryMode.fromCode(4))
                .isEqualTo(PullTaskAccountEntryMode.GROUP_CREATE_INITIAL);
    }

    @Test
    @DisplayName("未知进群方式仍返回 null，不因新增取值而改变兜底行为")
    void entryModeKeepsNullFallbackForUnknownCode() {
        assertThat(PullTaskAccountEntryMode.fromCode(null)).isNull();
        assertThat(PullTaskAccountEntryMode.fromCode(0)).isNull();
        assertThat(PullTaskAccountEntryMode.fromCode(5)).isNull();
    }

    @Test
    @DisplayName("执行阶段追加建群 9，既有八个阶段取值不变")
    void executionStageAppendsGroupCreateWithoutShiftingExistingCodes() {
        assertThat(PullTaskExecutionStage.LINK_VALIDATION.code()).isEqualTo(1);
        assertThat(PullTaskExecutionStage.MANAGER_JOIN.code()).isEqualTo(2);
        assertThat(PullTaskExecutionStage.MANAGER_ADMIN.code()).isEqualTo(3);
        assertThat(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code()).isEqualTo(4);
        assertThat(PullTaskExecutionStage.PULLER_INVITE.code()).isEqualTo(5);
        assertThat(PullTaskExecutionStage.PULL_EXECUTION.code()).isEqualTo(6);
        assertThat(PullTaskExecutionStage.MATERIAL_ADMIN.code()).isEqualTo(7);
        assertThat(PullTaskExecutionStage.CLOSING.code()).isEqualTo(8);
        assertThat(PullTaskExecutionStage.GROUP_CREATE.code()).isEqualTo(9);
    }

    @Test
    @DisplayName("两个枚举内部取值都不重复")
    void enumCodesAreUnique() {
        assertThat(Arrays.stream(PullTaskAccountEntryMode.values())
                .map(PullTaskAccountEntryMode::code).distinct().count())
                .isEqualTo(PullTaskAccountEntryMode.values().length);

        assertThat(Arrays.stream(PullTaskExecutionStage.values())
                .map(PullTaskExecutionStage::code).distinct().count())
                .isEqualTo(PullTaskExecutionStage.values().length);
    }
}
