package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.shared.exception.BusinessException;
import com.armada.task.model.dto.PullTaskStandardCreateDTO;
import com.armada.task.model.dto.PullTaskStandardGroupSettingDTO;
import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.model.enums.PullTaskDisappearingMessageMode;
import com.armada.task.model.enums.PullTaskEditPermissionMode;
import com.armada.task.model.enums.PullTaskGroupSettingTiming;
import com.armada.task.model.enums.PullTaskLinkPermissionMode;
import com.armada.task.model.enums.PullTaskMuteMode;
import com.armada.task.model.enums.PullTaskPullerSyncMode;
import com.armada.task.service.impl.PullTaskNewGroupModeValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 新群模式创建入参的校验规则。
 *
 * <p>校验规则是纯函数，不依赖数据库与 Spring 上下文，因此单独成类而不挤进
 * {@code PullTaskStandardCreateServiceTest}（那里每个用例都要起 H2 与 MyBatis）。</p>
 */
class PullTaskNewGroupModeValidatorTest {

    @Test
    @DisplayName("群链接模式不受影响：建群人分组为空也放行")
    void pastedLinkModeIgnoresCreatorGroup() {
        // 存量任务与既有前端都不会传建群相关字段，校验必须对它们完全透明。
        PullTaskNewGroupModeValidator.validateRequest(request(PullTaskCreationMode.PASTED_LINK, null, null));
    }

    @Test
    @DisplayName("群链接模式即使传了建群人分组也不报错，只是不生效")
    void pastedLinkModeToleratesCreatorGroup() {
        PullTaskNewGroupModeValidator.validateRequest(request(PullTaskCreationMode.PASTED_LINK, 21L, 3));
    }

    @Test
    @DisplayName("新群模式必须选建群人分组")
    void newGroupModeRequiresCreatorGroup() {
        assertThatThrownBy(() ->
                PullTaskNewGroupModeValidator.validateRequest(request(PullTaskCreationMode.NEW_GROUP, null, 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("建群人");
    }

    @Test
    @DisplayName("新群模式建群人分组齐全即通过，初始站台可以是 0")
    void newGroupModeAllowsZeroInitialStations() {
        PullTaskNewGroupModeValidator.validateRequest(request(PullTaskCreationMode.NEW_GROUP, 21L, 0));
    }

    @Test
    @DisplayName("初始站台数为负数不合法")
    void negativeInitialStationCountIsRejected() {
        assertThatThrownBy(() ->
                PullTaskNewGroupModeValidator.validateRequest(request(PullTaskCreationMode.NEW_GROUP, 21L, -1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("初始站台");
    }

    @Test
    @DisplayName("初始站台数大于 0 时必须选站台分组")
    void positiveInitialStationCountRequiresStationGroup() {
        // 与既有「每次拉站台数量>0 必须选站台分组」同一口径：
        // 建群时进群的站台同样来自站台分组，没有分组就无从选号。
        PullTaskStandardCreateDTO request = withStationGroup(
                request(PullTaskCreationMode.NEW_GROUP, 21L, 3), null, 0);

        assertThatThrownBy(() -> PullTaskNewGroupModeValidator.validateRequest(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("站台分组");
    }

    @Test
    @DisplayName("站台需求取两笔的较大值，不是相加")
    void stationDemandIsTheMaxOfTwoNeeds() {
        // 建群时占用的站台在拉人阶段会被既有选号逻辑排除（见规格 3.2），
        // 因此两笔需求不叠加；分组够满足较大的那笔即可。
        assertThat(PullTaskNewGroupModeValidator.stationDemand(3, 5)).isEqualTo(5);
        assertThat(PullTaskNewGroupModeValidator.stationDemand(7, 2)).isEqualTo(7);
        assertThat(PullTaskNewGroupModeValidator.stationDemand(0, 0)).isZero();
    }

    @Test
    @DisplayName("模式为空按群链接模式处理，兼容不传该字段的既有前端")
    void nullCreationModeFallsBackToPastedLink() {
        assertThat(PullTaskCreationMode.fromNullable(null))
                .isEqualTo(PullTaskCreationMode.PASTED_LINK);
        assertThat(PullTaskCreationMode.fromNullable(PullTaskCreationMode.NEW_GROUP))
                .isEqualTo(PullTaskCreationMode.NEW_GROUP);
    }

    private static PullTaskStandardCreateDTO request(
            PullTaskCreationMode creationMode, Long creatorGroupId, Integer initialStationCount) {
        return new PullTaskStandardCreateDTO(
                1L, "任务", null, 0, null, PullTaskPullerSyncMode.SINGLE,
                1, false, false, 1, 2, 3, 8, 30, 2, 2, 1,
                11L, 12L, 13L, null, null, groupSetting(),
                creationMode, creatorGroupId, initialStationCount, false);
    }

    private static PullTaskStandardCreateDTO withStationGroup(
            PullTaskStandardCreateDTO base, Long stationGroupId, int stationCountPerCall) {
        return new PullTaskStandardCreateDTO(
                base.draftTaskId(), base.taskName(), base.remark(), base.autoStart(),
                base.groupFolderId(), base.pullerSyncMode(), base.materialAdminTiming(),
                base.clearExistingMembers(), base.pullerJoinByLink(), base.earlyPullCount(),
                base.earlyPullCallCount(), base.pullCountMin(), base.pullCountMax(),
                base.pullIntervalSeconds(), base.pullerCountPerGroup(), stationCountPerCall,
                base.concurrentGroupCount(), base.managerGroupId(), base.pullerGroupId(),
                stationGroupId, base.managerFinishGroupId(), base.pullerFinishGroupId(),
                base.groupSetting(), base.creationMode(), base.creatorGroupId(),
                base.initialStationCount(), base.creatorLeaveAfterPull());
    }

    private static PullTaskStandardGroupSettingDTO groupSetting() {
        return new PullTaskStandardGroupSettingDTO(
                true, PullTaskGroupSettingTiming.AFTER_PULL, "客户群", false, null, null,
                false, false, PullTaskEditPermissionMode.UNCHANGED,
                PullTaskMuteMode.UNCHANGED, PullTaskLinkPermissionMode.ADMIN_ONLY,
                PullTaskDisappearingMessageMode.UNCHANGED);
    }
}
