package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskStandardGroupSetting;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskDisappearingMessageMode;
import com.armada.task.model.enums.PullTaskEditPermissionMode;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupSettingTiming;
import com.armada.task.model.enums.PullTaskLinkPermissionMode;
import com.armada.task.model.enums.PullTaskMuteMode;
import com.armada.task.model.vo.PullTaskGroupAvatarContent;
import com.armada.task.service.PullTaskGroupAvatarService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「群信息设置」下发命令的载荷取值。
 *
 * <p>契约（业务方 2026-08-19 拍板）：整块群资料另起一条命令 {@code group.profile.apply}，
 * 来源 {@code pull_task_group_profile}，不与建群链路混用，也不与旧的单项设置命令
 * {@code pull_task_group_settings} 混用。字段名逐字照契约：群名 {@code subject}、头像
 * {@code avatar}（{@code base64} + {@code mimetype}）、群描述 {@code description}、禁言
 * {@code sendMessagesAllowed}、群资料编辑权限 {@code editGroupSettingsAllowed}、加人权限
 * {@code addMembersAllowed}、入群审批 {@code joinApprovalEnabled}、限时消息
 * {@code ephemeralDurationSeconds}。换名字不会报错，只会让设置被协议层静默忽略。</p>
 *
 * <p>本测试只钉业务方确认过的几条：群名取哪个值、「不操作」怎么表达、头像与描述是否同命令。
 * 各设置项具体的取值映射（例如限时消息几天折算多少秒）业务方没有给口径，测试不替它规定。</p>
 */
class PullTaskGroupSettingsApplyPayloadTest {

    private static final String AVATAR_FILE_KEY = "avatar-abc123.png";

    /** 运营上传的原图：非方形、PNG 透明底，转码后必须变成 640×640 方形 JPEG。 */
    private static final byte[] AVATAR_SOURCE_PNG = transparentPng(300, 150);

    private final PullTaskAccountActionMapper actionMapper =
            mock(PullTaskAccountActionMapper.class);
    private final PullTaskGroupAccountMapper accountMapper =
            mock(PullTaskGroupAccountMapper.class);
    private final PullTaskGroupExecutionMapper executionMapper =
            mock(PullTaskGroupExecutionMapper.class);
    private final PullTaskStandardGroupSettingMapper groupSettingMapper =
            mock(PullTaskStandardGroupSettingMapper.class);
    private final PullTaskGroupAvatarService avatarService =
            mock(PullTaskGroupAvatarService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PullTaskGroupProfilePayloadHydrator hydrator =
            new PullTaskGroupProfilePayloadHydrator(
                    actionMapper, accountMapper, executionMapper, groupSettingMapper,
                    avatarService, objectMapper);

    // ---------- 断言 3：整块群资料走自己的命令，不蹭建群那条 ----------

    /**
     * 命令类型与来源是协议两端分派执行器的唯一依据。
     *
     * <p>蹭建群那条命令会连它「字段全必填」的口径一起继承，把运营没勾的项按默认值覆写到老群；
     * 蹭旧的单项命令 {@code pull_task_group_settings} 则会让结果回来时分不清是哪一条。</p>
     */
    @Test
    @DisplayName("整块群资料走 group.profile.apply 命令，来源是 pull_task_group_profile")
    void groupProfileUsesItsOwnCommandTypeAndSource() throws Exception {
        assertThat(hydrator.supports(outbox())).isTrue();

        ProtocolCommandOutbox groupCreationCommand = outbox();
        groupCreationCommand.setCommandType("group.normal_creation.requested");
        assertThat(hydrator.supports(groupCreationCommand)).isFalse();

        ProtocolCommandOutbox singleSettingCommand = outbox();
        singleSettingCommand.setCommandType("group.settings.requested");
        assertThat(hydrator.supports(singleSettingCommand)).isFalse();

        JsonNode payload = hydrate(enabledSetting(), "印度料子包.txt");

        assertThat(payload.path("source").asText()).isEqualTo("pull_task_group_profile");
        // 老群改的是这个群，群 JID 必填，没有它协议侧无从定位。
        assertThat(payload.path("groupJid").asText()).isEqualTo("120363group@g.us");
    }

    // ---------- 断言 4：群名取表单值，勾选后取料子文件名 ----------

    @Test
    @DisplayName("群名取表单里填的值")
    void payloadSubjectUsesFormGroupName() throws Exception {
        PullTaskStandardGroupSetting setting = enabledSetting();
        setting.setGroupName("客户群");
        setting.setMaterialFilenameAsGroupName(0);

        JsonNode payload = hydrate(setting, "印度料子包.txt");

        assertThat(payload.path("subject").asText()).isEqualTo("客户群");
    }

    @Test
    @DisplayName("勾选「料子文件名为群名」时群名取该执行行的料子文件名")
    void payloadSubjectUsesMaterialFileNameWhenChosen() throws Exception {
        PullTaskStandardGroupSetting setting = enabledSetting();
        // 手填群名同时存在也不算数：勾选后群名按每条执行行的料子文件名逐行不同。
        setting.setGroupName("不应下发的手填群名");
        setting.setMaterialFilenameAsGroupName(1);

        JsonNode payload = hydrate(setting, "印度料子包.txt");

        assertThat(payload.path("subject").asText()).isEqualTo("印度料子包.txt");
    }

    // ---------- 断言 5：选「不操作」的项，载荷里留空 ----------

    /**
     * 拉群进的是老群，「不操作」意味着绝不碰人家群里现有的设置。
     *
     * <p>契约把「留空」钉死为**字段整个不出现**：填一个具体的 true/false/0 会被协议层照着执行，
     * 等于替客户把老群的现有设置改掉；发一个显式的 {@code null} 同样危险——协议端无从判断它是
     * 「别动」还是「清空」。</p>
     *
     * <p>前半段是对照组：选了具体选项的同名字段必须真的带值出现。少了它，一个干脆什么都不发的
     * 实现也能让「留空」全绿。对照组只查有没有值，不查值是什么——各项的取值映射业务方本轮
     * 没给口径，测试不替它规定。</p>
     */
    @Test
    @DisplayName("选「不操作」的设置项在载荷里不出现，选了具体选项的照常带值")
    void unchangedFormChoicesLeavePayloadFieldsEmpty() throws Exception {
        PullTaskStandardGroupSetting chosen = enabledSetting();
        chosen.setMuteMode(PullTaskMuteMode.MUTE.code());
        chosen.setEditPermissionMode(PullTaskEditPermissionMode.ALLOW.code());
        chosen.setDisappearingMessageMode(PullTaskDisappearingMessageMode.SEVEN_DAYS.code());

        JsonNode chosenPayload = hydrate(chosen, "印度料子包.txt");

        assertPresent(chosenPayload, "sendMessagesAllowed");
        assertPresent(chosenPayload, "editGroupSettingsAllowed");
        assertPresent(chosenPayload, "ephemeralDurationSeconds");

        PullTaskStandardGroupSetting setting = enabledSetting();
        setting.setMuteMode(PullTaskMuteMode.UNCHANGED.code());
        setting.setEditPermissionMode(PullTaskEditPermissionMode.UNCHANGED.code());
        setting.setDisappearingMessageMode(PullTaskDisappearingMessageMode.UNCHANGED.code());

        JsonNode payload = hydrate(setting, "印度料子包.txt");

        assertEmpty(payload, "sendMessagesAllowed");
        assertEmpty(payload, "editGroupSettingsAllowed");
        assertEmpty(payload, "ephemeralDurationSeconds");
    }

    // ---------- 群链接权限与编辑群资料权限是同一个开关 ----------

    /**
     * 「群链接权限」不再单独下发，它就是编辑群资料权限。
     *
     * <p>WhatsApp 底层能设的群权限只有「谁能发消息」和「谁能编辑群资料」两个，取邀请链接的权限
     * 绑在后者上，没有独立开关。表单上并排放两项必然有一项不生效，因此合并。</p>
     *
     * <p>顺带钉住：{@code addMembersAllowed} 不许由 {@code link_permission_mode} 驱动——加人权限
     * 与取链接权限不是一回事，接上等于替运营下发一个他没表达过的权限变更。表单目前没有这一项，
     * 因此该字段恒不出现。</p>
     */
    @Test
    @DisplayName("群链接权限并入编辑群资料权限，不再单独下发加人权限")
    void linkPermissionIsMergedIntoEditPermission() throws Exception {
        PullTaskStandardGroupSetting setting = enabledSetting();
        // 该列仍有值（表单历史遗留），但补全器不许读它。
        setting.setLinkPermissionMode(PullTaskLinkPermissionMode.ALL.code());
        setting.setEditPermissionMode(PullTaskEditPermissionMode.UNCHANGED.code());

        JsonNode payload = hydrate(setting, "印度料子包.txt");

        assertEmpty(payload, "addMembersAllowed");
        // 编辑群资料选了「不操作」，链接权限那一列有值也不得把它顶出来。
        assertEmpty(payload, "editGroupSettingsAllowed");
    }

    /** 选了具体选项就必须带值；只查有没有，不查具体值。 */
    private static void assertPresent(JsonNode payload, String field) {
        assertThat(payload.hasNonNull(field))
                .as("设置项 %s 选了具体选项，载荷必须带值下发", field)
                .isTrue();
    }

    // ---------- 断言 9：群头像与群描述同命令下发 ----------

    /**
     * 头像和描述不另起命令、不另起 topic，跟群名挤同一条 {@code group.profile.apply}。
     *
     * <p>头像走 base64 内嵌：协议层进程读不到 armada 的本地盘，给 URL 拉不到；契约定的形状是
     * {@code {base64, mimetype}}，且内容已经是 armada 转好的 640×640 方形 JPEG，协议两侧纯透传。
     * 转码本身的用例在 {@code PullTaskGroupAvatarJpegTranscoderTest}，这里只钉「同一条命令里
     * 带出来的是转好的 JPEG」。</p>
     */
    @Test
    @DisplayName("表单填了群头像和群描述，同一条命令的载荷里带上这两项")
    void payloadCarriesAvatarAndDescriptionInTheSameCommand() throws Exception {
        PullTaskStandardGroupSetting setting = enabledSetting();
        setting.setAvatarFileKey(AVATAR_FILE_KEY);
        setting.setGroupDescription("本群仅发布客户通知");

        JsonNode payload = hydrate(setting, "印度料子包.txt");

        assertThat(payload.path("description").asText()).isEqualTo("本群仅发布客户通知");
        assertThat(payload.path("avatar").path("mimetype").asText()).isEqualTo("image/jpeg");
        byte[] avatar = Base64.getDecoder()
                .decode(payload.path("avatar").path("base64").asText());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(avatar));
        assertThat(decoded).as("头像必须是能解码的图片").isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(640);
        assertThat(decoded.getHeight()).isEqualTo(640);
    }

    @Test
    @DisplayName("没填群头像和群描述时这两项在载荷里不出现")
    void payloadLeavesAvatarAndDescriptionEmptyWhenNotFilled() throws Exception {
        PullTaskStandardGroupSetting setting = enabledSetting();
        setting.setAvatarFileKey(null);
        setting.setGroupDescription(null);

        JsonNode payload = hydrate(setting, "印度料子包.txt");

        assertEmpty(payload, "description");
        assertEmpty(payload, "avatar");
    }

    /** 「留空」= 字段整个不出现；出现即视为要求协议层去改这一项，显式 null 同样不允许。 */
    private static void assertEmpty(JsonNode payload, String field) {
        assertThat(payload.has(field))
                .as("设置项 %s 选了不操作/没填，载荷里不得出现该字段，实际=%s", field, payload.get(field))
                .isFalse();
    }

    private JsonNode hydrate(PullTaskStandardGroupSetting setting, String sourceFileName)
            throws Exception {
        ProtocolCommandOutbox row = outbox();
        when(actionMapper.selectByCommandId("cmd-profile-1"))
                .thenReturn(action(PullTaskAccountActionType.APPLY_GROUP_SETTINGS));
        when(accountMapper.selectById(501L)).thenReturn(manager());
        PullTaskGroupExecution execution = execution();
        execution.setSourceFileName(sourceFileName);
        when(executionMapper.selectById(11L)).thenReturn(execution);
        when(groupSettingMapper.selectByTaskId(100L)).thenReturn(setting);
        when(avatarService.content(7L, AVATAR_FILE_KEY))
                .thenReturn(new PullTaskGroupAvatarContent("image/png", AVATAR_SOURCE_PNG));
        return hydrator.hydrate(row, objectMapper.readTree(row.getPayloadJson()));
    }

    /** 造一张带透明底的非方形 PNG 当运营上传的原图。 */
    private static byte[] transparentPng(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", output);
        } catch (IOException e) {
            throw new IllegalStateException("测试原图生成失败", e);
        }
        return output.toByteArray();
    }

    /** 一份总开关已开、其余项按用例覆盖的「群信息设置」。 */
    private static PullTaskStandardGroupSetting enabledSetting() {
        PullTaskStandardGroupSetting row = new PullTaskStandardGroupSetting();
        row.setTenantId(7L);
        row.setTaskId(100L);
        row.setGroupSettingEnabled(1);
        row.setSettingTiming(PullTaskGroupSettingTiming.BEFORE_PULL.code());
        row.setGroupName("客户群");
        row.setMaterialFilenameAsGroupName(0);
        row.setGroupDescription("群说明");
        row.setAutoUnmuteAfterTask(0);
        row.setAutoCloseInviteAfterTask(0);
        row.setEditPermissionMode(PullTaskEditPermissionMode.DISALLOW.code());
        row.setMuteMode(PullTaskMuteMode.MUTE.code());
        row.setLinkPermissionMode(PullTaskLinkPermissionMode.ADMIN_ONLY.code());
        row.setDisappearingMessageMode(PullTaskDisappearingMessageMode.SEVEN_DAYS.code());
        return row;
    }

    private static ProtocolCommandOutbox outbox() {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(7L);
        row.setCommandId("cmd-profile-1");
        row.setCommandType("group.profile.apply");
        row.setAggregateType("PULL_TASK_ACCOUNT_ACTION");
        row.setAggregateId(811L);
        row.setProtocolAccountId("manager-901");
        row.setProtocolBackend("WEB");
        row.setPayloadJson("""
                {"tenantId":7,"pullTaskId":100,"groupExecutionId":11,
                 "actionId":811,"source":"pull_task_group_profile"}
                """);
        return row;
    }

    private static PullTaskAccountAction action(PullTaskAccountActionType type) {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setId(811L);
        row.setTenantId(7L);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setActionType(type.code());
        // 群设置没有对象账号，actor 与 target 同为管理员角色行本身。
        row.setActorGroupAccountId(501L);
        row.setTargetGroupAccountId(501L);
        row.setActionStatus(PullTaskActionStatus.SUBMITTED.code());
        row.setCommandId("cmd-profile-1");
        row.setAttemptNo(2);
        return row;
    }

    private static PullTaskGroupAccount manager() {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setId(501L);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setAccountId(901L);
        row.setAccountPhone("8613800000901");
        row.setRoleType(PullTaskGroupAccountRole.MANAGER.code());
        return row;
    }

    private static PullTaskGroupExecution execution() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTaskId(100L);
        row.setGroupJid("120363group@g.us");
        return row;
    }
}
