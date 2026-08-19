package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskGroupSettingsCallback;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskGroupSettingItem;
import com.armada.task.model.enums.PullTaskGroupSettingsProtocolOutcome;
import com.armada.task.scheduler.PullTaskExecutionDispatchProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 「群信息设置」下发结果的收敛口径。
 *
 * <p>业务口径（2026-08-18 确认）：设置失败不阻断执行行，只把失败原因留在动作行上；设置成功
 * 就地收敛，不再重复下发。群资料是运营展示需求，拉不拉得到人与它无关，让它阻断执行行等于用
 * 一个展示层问题卡死整条行。</p>
 */
class PullTaskGroupSettingsApplyResultTest {

    private static final long ACTION_ID = 812L;
    private static final String COMMAND_ID = "cmd-settings-1";
    private static final int ATTEMPT_NO = 2;

    private final PullTaskAccountActionMapper actionMapper =
            mock(PullTaskAccountActionMapper.class);
    private final PullTaskGroupAccountMapper accountMapper =
            mock(PullTaskGroupAccountMapper.class);
    private final PullTaskGroupExecutionMapper executionMapper =
            mock(PullTaskGroupExecutionMapper.class);
    private final AccountProtocolLookupService accountLookup =
            mock(AccountProtocolLookupService.class);
    private final ProtocolCommandOutboxService outboxService =
            mock(ProtocolCommandOutboxService.class);
    private final PullTaskExecutionDispatchProperties properties =
            new PullTaskExecutionDispatchProperties();

    private final PullTaskGroupSettingsResultServiceImpl service =
            new PullTaskGroupSettingsResultServiceImpl(
                    actionMapper, accountMapper, executionMapper, accountLookup,
                    outboxService, properties);

    // ---------- 断言 6：失败不阻断，原因码落库 ----------

    @Test
    @DisplayName("群设置失败只写动作行，执行行照常继续")
    void failedGroupSettingsNeverTouchExecutionRow() {
        givenSubmittedAction();
        givenActionCasSucceeds();

        assertThat(service.apply(failureOn(PullTaskGroupSettingItem.GROUP_NAME))).isTrue();

        verifyNoInteractions(executionMapper);
    }

    @Test
    @DisplayName("群名没设上把 GROUP_NAME_SET_FAILED 落到动作行")
    void failedGroupSettingsPersistGroupNameSetFailed() {
        givenSubmittedAction();
        givenActionCasSucceeds();

        service.apply(failureOn(PullTaskGroupSettingItem.GROUP_NAME));

        verifyReasonCode(PullTaskExecutionReasonCode.GROUP_NAME_SET_FAILED);
    }

    // ---------- 断言 10：失败原因细化到具体设置项 ----------

    @Test
    @DisplayName("群头像没设上落 GROUP_AVATAR_SET_FAILED")
    void failedAvatarPersistsAvatarReasonCode() {
        givenSubmittedAction();
        givenActionCasSucceeds();

        service.apply(failureOn(PullTaskGroupSettingItem.AVATAR));

        verifyReasonCode(PullTaskExecutionReasonCode.GROUP_AVATAR_SET_FAILED);
    }

    @Test
    @DisplayName("群描述没设上落 GROUP_DESCRIPTION_SET_FAILED")
    void failedDescriptionPersistsDescriptionReasonCode() {
        givenSubmittedAction();
        givenActionCasSucceeds();

        service.apply(failureOn(PullTaskGroupSettingItem.DESCRIPTION));

        verifyReasonCode(PullTaskExecutionReasonCode.GROUP_DESCRIPTION_SET_FAILED);
    }

    @Test
    @DisplayName("群禁言没设上落 GROUP_MUTE_SET_FAILED")
    void failedMutePersistsMuteReasonCode() {
        givenSubmittedAction();
        givenActionCasSucceeds();

        service.apply(failureOn(PullTaskGroupSettingItem.MUTE));

        verifyReasonCode(PullTaskExecutionReasonCode.GROUP_MUTE_SET_FAILED);
    }

    /**
     * 八个设置项必须各自落到不同的原因码。
     *
     * <p>逐项用例只能证明「这一项对上了」，挡不住把八项全映射成同一个码这种偷懒实现——那样运营
     * 看到的还是笼统一句「设置失败」，等于没拆。本用例把八项各跑一遍收集真正写进动作行的
     * 原因码，要求两两不同。</p>
     */
    @Test
    @DisplayName("八个设置项的失败原因码两两不同，不得共用一个笼统码")
    void everyGroupSettingItemHasItsOwnReasonCode() {
        List<String> reasonCodes = new ArrayList<>();
        for (PullTaskGroupSettingItem item : PullTaskGroupSettingItem.values()) {
            reasonCodes.add(capturedReasonCodeFor(item));
        }

        assertThat(reasonCodes)
                .as("每个设置项都要有自己的原因码，实际=%s", reasonCodes)
                .doesNotContainNull()
                .doesNotHaveDuplicates()
                .hasSize(PullTaskGroupSettingItem.values().length);
    }

    /** 单独跑一项失败，取回它实际写进动作行的原因码。 */
    private String capturedReasonCodeFor(PullTaskGroupSettingItem item) {
        PullTaskAccountActionMapper mapper = mock(PullTaskAccountActionMapper.class);
        when(mapper.selectByCommandId(COMMAND_ID))
                .thenReturn(action(PullTaskAccountActionType.APPLY_GROUP_SETTINGS));
        when(mapper.transitionManagerAdminResult(
                anyLong(), anyString(), anyInt(), anyList(), anyInt(),
                anyBoolean(), any(), any(), anyLong()))
                .thenReturn(1);
        new PullTaskGroupSettingsResultServiceImpl(
                mapper, accountMapper, executionMapper, accountLookup, outboxService, properties)
                .apply(failureOn(item));

        ArgumentCaptor<String> reasonCode = ArgumentCaptor.forClass(String.class);
        verify(mapper).transitionManagerAdminResult(
                anyLong(), anyString(), anyInt(), anyList(), anyInt(),
                anyBoolean(), reasonCode.capture(), any(), anyLong());
        return reasonCode.getValue();
    }

    private void verifyReasonCode(PullTaskExecutionReasonCode expected) {
        verify(actionMapper).transitionManagerAdminResult(
                eq(ACTION_ID), eq(COMMAND_ID), eq(ATTEMPT_NO), anyList(),
                eq(PullTaskActionStatus.FAILED.code()),
                eq(false), eq(expected.name()), anyString(), anyLong());
    }

    // ---------- 断言 7：成功收敛，不重复下发 ----------

    @Test
    @DisplayName("群设置成功把动作行收敛为成功")
    void succeededGroupSettingsConvergeActionToSuccess() {
        givenSubmittedAction();
        givenActionCasSucceeds();

        service.apply(callback(PullTaskGroupSettingsProtocolOutcome.SUCCESS, null));

        verify(actionMapper).transitionManagerAdminResult(
                eq(ACTION_ID), eq(COMMAND_ID), eq(ATTEMPT_NO), anyList(),
                eq(PullTaskActionStatus.SUCCESS.code()),
                eq(false), eq(null), eq(null), anyLong());
    }

    @Test
    @DisplayName("群设置成功后不再下发第二条群设置命令")
    void succeededGroupSettingsDoNotEnqueueAnotherCommand() {
        givenSubmittedAction();
        givenActionCasSucceeds();

        assertThat(service.apply(callback(
                PullTaskGroupSettingsProtocolOutcome.SUCCESS, null))).isTrue();

        verify(outboxService, never()).enqueuePullTaskGroupSettingsCommands(anyList());
    }

    // ---------- fixtures ----------

    private void givenSubmittedAction() {
        when(actionMapper.selectByCommandId(COMMAND_ID))
                .thenReturn(action(PullTaskAccountActionType.APPLY_GROUP_SETTINGS));
    }

    private void givenActionCasSucceeds() {
        when(actionMapper.transitionManagerAdminResult(
                anyLong(), anyString(), anyInt(), anyList(), anyInt(),
                anyBoolean(), any(), any(), anyLong()))
                .thenReturn(1);
    }

    private static PullTaskGroupSettingsCallback callback(
            PullTaskGroupSettingsProtocolOutcome outcome, String reasonCode) {
        return new PullTaskGroupSettingsCallback(
                7L, 100L, 11L, ACTION_ID, 901L, "manager-901",
                COMMAND_ID, ATTEMPT_NO, outcome, null, reasonCode, "raw", 1_000L);
    }

    /** 一条明确指出是哪一项没设上的失败回调。 */
    private static PullTaskGroupSettingsCallback failureOn(PullTaskGroupSettingItem item) {
        return new PullTaskGroupSettingsCallback(
                7L, 100L, 11L, ACTION_ID, 901L, "manager-901",
                COMMAND_ID, ATTEMPT_NO, PullTaskGroupSettingsProtocolOutcome.FAILED,
                item, "GROUP_SETTINGS_APPLY_FAILED", "raw", 1_000L);
    }

    private static PullTaskAccountAction action(PullTaskAccountActionType type) {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setId(ACTION_ID);
        row.setTenantId(7L);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setActionType(type.code());
        // 群设置没有对象账号，actor 与 target 同为管理员角色行本身。
        row.setActorGroupAccountId(501L);
        row.setTargetGroupAccountId(501L);
        row.setActionStatus(PullTaskActionStatus.SUBMITTED.code());
        row.setCommandId(COMMAND_ID);
        row.setAttemptNo(ATTEMPT_NO);
        return row;
    }
}
