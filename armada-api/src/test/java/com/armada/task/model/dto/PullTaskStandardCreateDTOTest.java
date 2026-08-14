package com.armada.task.model.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.task.model.enums.PullTaskDisappearingMessageMode;
import com.armada.task.model.enums.PullTaskEditPermissionMode;
import com.armada.task.model.enums.PullTaskGroupSettingTiming;
import com.armada.task.model.enums.PullTaskLinkPermissionMode;
import com.armada.task.model.enums.PullTaskMuteMode;
import com.armada.task.model.enums.PullTaskPullerSyncMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 普通群链接大表单 JSON 合同测试。 */
public class PullTaskStandardCreateDTOTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesEveryApprovedFieldWithExactApiNames() throws Exception {
        PullTaskStandardCreateDTO request = request();

        String json = objectMapper.writeValueAsString(request);

        assertThat(json).isEqualTo("{\"draftTaskId\":1,\"taskName\":\"任务\","
                + "\"remark\":\"备注\",\"autoStart\":0,"
                + "\"groupFolderId\":18,\"pullerSyncMode\":\"BATCH\","
                + "\"materialAdminTiming\":1,\"clearExistingMembers\":true,"
                + "\"pullerJoinByLink\":true,"
                + "\"earlyPullCount\":1,\"earlyPullCallCount\":2,"
                + "\"pullCountMin\":3,\"pullCountMax\":8,\"pullIntervalSeconds\":30,"
                + "\"pullerCountPerGroup\":2,\"stationCountPerCall\":0,"
                + "\"concurrentGroupCount\":1,"
                + "\"managerGroupId\":11,\"pullerGroupId\":12,\"stationGroupId\":null,"
                + "\"managerFinishGroupId\":14,\"pullerFinishGroupId\":15,"
                + "\"groupSetting\":{\"settingTiming\":\"AFTER_PULL\","
                + "\"groupName\":\"客户群\",\"useMaterialFileNameAsGroupName\":false,"
                + "\"avatarFileKey\":\"avatar.png\",\"groupDescription\":\"说明\","
                + "\"autoCloseMuteAfterTask\":true,\"autoCloseInviteAfterTask\":true,"
                + "\"editPermission\":\"DISALLOW\",\"muteMode\":\"MUTE\","
                + "\"linkPermission\":\"ADMIN_ONLY\","
                + "\"disappearingMessage\":\"SEVEN_DAYS\"}}");
        assertThat(json).doesNotContain("\"version\"");
        assertThat(objectMapper.readValue(json, PullTaskStandardCreateDTO.class)).isEqualTo(request);
    }

    public static PullTaskStandardCreateDTO request() {
        PullTaskStandardGroupSettingDTO groupSetting = new PullTaskStandardGroupSettingDTO(
                PullTaskGroupSettingTiming.AFTER_PULL, "客户群", false, "avatar.png", "说明",
                true, true, PullTaskEditPermissionMode.DISALLOW, PullTaskMuteMode.MUTE,
                PullTaskLinkPermissionMode.ADMIN_ONLY,
                PullTaskDisappearingMessageMode.SEVEN_DAYS);
        return new PullTaskStandardCreateDTO(
                1L, "任务", "备注", 0, 18L, PullTaskPullerSyncMode.BATCH,
                1, true, true, 1, 2, 3, 8, 30, 2, 0, 1,
                11L, 12L, null, 14L, 15L, groupSetting);
    }
}
