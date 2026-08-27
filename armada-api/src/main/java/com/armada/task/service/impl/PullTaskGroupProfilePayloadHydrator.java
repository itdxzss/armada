package com.armada.task.service.impl;

import com.armada.platform.protocol.model.command.ProtocolPullTaskGroupProfileCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskGroupProfilePayload;
import com.armada.platform.protocol.model.command.ProtocolPullTaskParticipantActionReference;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.service.ProtocolCommandPayloadHydrator;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskStandardGroupSetting;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskDisappearingMessageMode;
import com.armada.task.model.enums.PullTaskEditPermissionMode;
import com.armada.task.model.enums.PullTaskMuteMode;
import com.armada.task.model.vo.PullTaskGroupAvatarContent;
import com.armada.task.service.PullTaskGroupAvatarService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 从任务级「群信息设置」配置、管理员角色快照和执行行补全整块群资料 payload。
 *
 * <p>与同域的 {@link PullTaskGroupSettingsPayloadHydrator} 分工：那个补全一条命令一个设置项的
 * 旧单项命令（放开加人权限、关闭进群审核），本类补全 {@code group.profile.apply} 这条整块命令。</p>
 *
 * <p>核心规则是「留空即别动」：运营在表单里选了「不操作」的项，payload 里对应字段传 {@code null}，
 * 由 {@link ProtocolPullTaskGroupProfilePayload} 的 NON_NULL 序列化保证字段整个不出现。老群里的
 * 现有设置是客户自己配的，把没选的项按默认值发下去等于替客户改群。</p>
 *
 * <p>任务级配置行不进 Outbox 引用，发命令时现取：运营随时可以改任务配置，现取才是唯一事实。</p>
 */
@Component
public class PullTaskGroupProfilePayloadHydrator implements ProtocolCommandPayloadHydrator {

    private static final int TIMEOUT_MS = 30_000;

    /** 「群信息设置」总开关的开启值。 */
    private static final int SETTING_ENABLED = 1;

    /** 勾选「料子文件名为群名」的值。 */
    private static final int MATERIAL_FILENAME_AS_GROUP_NAME = 1;

    /** 限时消息各档对应的秒数；0 表示关闭限时消息。 */
    private static final int EPHEMERAL_ONE_DAY_SECONDS = 86_400;
    private static final int EPHEMERAL_SEVEN_DAYS_SECONDS = 604_800;
    private static final int EPHEMERAL_NINETY_DAYS_SECONDS = 7_776_000;
    private static final int EPHEMERAL_OFF_SECONDS = 0;

    private final PullTaskAccountActionMapper actionMapper;
    private final PullTaskGroupAccountMapper accountMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final PullTaskStandardGroupSettingMapper groupSettingMapper;
    private final PullTaskGroupAvatarService avatarService;
    private final ObjectMapper objectMapper;

    /** 创建「群信息设置」payload 补全器。 */
    public PullTaskGroupProfilePayloadHydrator(
            PullTaskAccountActionMapper actionMapper,
            PullTaskGroupAccountMapper accountMapper,
            PullTaskGroupExecutionMapper executionMapper,
            PullTaskStandardGroupSettingMapper groupSettingMapper,
            PullTaskGroupAvatarService avatarService,
            ObjectMapper objectMapper) {
        this.actionMapper = actionMapper;
        this.accountMapper = accountMapper;
        this.executionMapper = executionMapper;
        this.groupSettingMapper = groupSettingMapper;
        this.avatarService = avatarService;
        this.objectMapper = objectMapper;
    }

    /** 仅处理普通拉群账号动作聚合上的整块群资料命令。 */
    @Override
    public boolean supports(ProtocolCommandOutbox row) {
        return row != null
                && ProtocolPullTaskGroupProfileCommandRequest.COMMAND_TYPE
                        .equals(row.getCommandType())
                && ProtocolPullTaskGroupProfileCommandRequest.AGGREGATE_TYPE
                        .equals(row.getAggregateType());
    }

    /** {@inheritDoc} */
    @Override
    public JsonNode hydrate(ProtocolCommandOutbox row, JsonNode referencePayload) {
        ProtocolPullTaskParticipantActionReference reference = parse(referencePayload);
        validateReference(row, reference);
        Long previousTenant = TenantContext.get();
        TenantContext.set(reference.tenantId());
        try {
            PullTaskAccountAction action = actionMapper.selectByCommandId(row.getCommandId());
            if (!validAction(action, row, reference)) {
                throw validation("拉群群信息设置命令关联动作不一致 commandId=" + row.getCommandId());
            }
            PullTaskGroupAccount manager =
                    accountMapper.selectById(action.getActorGroupAccountId());
            PullTaskGroupExecution execution =
                    executionMapper.selectById(reference.groupExecutionId());
            if (!validManager(manager, reference) || !validExecution(execution, reference)) {
                throw validation("拉群群信息设置命令冻结事实不完整 commandId=" + row.getCommandId());
            }
            PullTaskStandardGroupSetting setting =
                    groupSettingMapper.selectByTaskId(reference.pullTaskId());
            requireEnabled(setting);
            return objectMapper.valueToTree(
                    payload(row, reference, action, manager, execution, setting));
        } finally {
            restoreTenant(previousTenant);
        }
    }

    /**
     * 组装 wire payload：路由事实来自 Outbox 与角色快照，设置项来自任务级配置行。
     *
     * <p>入参已由 {@link #hydrate} 校验通过，本方法只做取值与映射。</p>
     */
    private ProtocolPullTaskGroupProfilePayload payload(
            ProtocolCommandOutbox row,
            ProtocolPullTaskParticipantActionReference reference,
            PullTaskAccountAction action,
            PullTaskGroupAccount manager,
            PullTaskGroupExecution execution,
            PullTaskStandardGroupSetting setting) {
        return new ProtocolPullTaskGroupProfilePayload(
                reference.tenantId(),
                reference.pullTaskId(),
                reference.groupExecutionId(),
                reference.actionId(),
                manager.getAccountId(),
                row.getProtocolAccountId(),
                manager.getAccountPhone(),
                backend(row).name(),
                execution.getGroupJid(),
                action.getAttemptNo(),
                TIMEOUT_MS,
                reference.source(),
                subject(setting, execution),
                avatar(reference.tenantId(), setting),
                trimToNull(setting.getGroupDescription()),
                sendMessagesAllowed(setting.getMuteMode()),
                editGroupSettingsAllowed(setting.getEditPermissionMode()),
                addMembersAllowed(),
                joinApprovalEnabled(),
                ephemeralDurationSeconds(setting.getDisappearingMessageMode()));
    }

    /**
     * 群名：勾选「料子文件名为群名」时逐行取该执行行配对的料子文件名，否则取表单手填值。
     *
     * <p>勾选后手填值不算数：一个任务下每条执行行的料子文件不同，群名本就该逐行不同。</p>
     */
    private static String subject(
            PullTaskStandardGroupSetting setting, PullTaskGroupExecution execution) {
        boolean useMaterialFileName = setting.getMaterialFilenameAsGroupName() != null
                && setting.getMaterialFilenameAsGroupName() == MATERIAL_FILENAME_AS_GROUP_NAME;
        return useMaterialFileName
                ? trimToNull(execution.getSourceFileName())
                : trimToNull(setting.getGroupName());
    }

    /**
     * 群头像：从本地盘按 {@code avatar_file_key} 读出原图，转成 640×640 方形 JPEG 后 base64 内嵌。
     *
     * <p>协议层进程读不到 armada 的本地盘，给它 URL 拉不到；头像限 500KB 以内，内嵌可行。
     * 转码放在这里而不是协议侧，两条协议路径才一致，理由见
     * {@link PullTaskGroupAvatarJpegTranscoder}。</p>
     *
     * <p>文件读不出来或转不动时直接抛业务异常，而不是静默丢头像：静默丢会让运营以为头像设上了。</p>
     */
    private ProtocolPullTaskGroupProfilePayload.Avatar avatar(
            Long tenantId, PullTaskStandardGroupSetting setting) {
        String fileKey = trimToNull(setting.getAvatarFileKey());
        if (fileKey == null) {
            return null;
        }
        PullTaskGroupAvatarContent content =
                avatarService.contentForTaskExecution(tenantId, fileKey);
        byte[] jpeg = PullTaskGroupAvatarJpegTranscoder.toSquareJpeg(content.content());
        return new ProtocolPullTaskGroupProfilePayload.Avatar(
                Base64.getEncoder().encodeToString(jpeg),
                PullTaskGroupAvatarJpegTranscoder.MIMETYPE);
    }

    /** 群禁言：禁言即只允许管理员发言，因此取值与「允许全体成员发言」相反。 */
    private static Boolean sendMessagesAllowed(Integer muteMode) {
        if (muteMode == null) {
            return null;
        }
        return switch (PullTaskMuteMode.fromCode(muteMode)) {
            case UNCHANGED -> null;
            case MUTE -> Boolean.FALSE;
            case UNMUTE -> Boolean.TRUE;
        };
    }

    /**
     * 群资料编辑权限：是否允许全体成员改群名、群头像、群描述。
     *
     * <p>它同时决定谁能拿到群邀请链接：WhatsApp 底层可设的群权限只有「谁能发消息」和「谁能编辑
     * 群资料」两个，取邀请链接的权限绑在后者上，没有独立开关。因此表单上的「群链接权限」与
     * 「编辑群资料权限」本就是同一个开关，合并成本项，{@code link_permission_mode} 列不再读。</p>
     */
    private static Boolean editGroupSettingsAllowed(Integer editPermissionMode) {
        if (editPermissionMode == null) {
            return null;
        }
        return switch (PullTaskEditPermissionMode.fromCode(editPermissionMode)) {
            case UNCHANGED -> null;
            case ALLOW -> Boolean.TRUE;
            case DISALLOW -> Boolean.FALSE;
        };
    }

    /**
     * 加人权限：契约保留该字段，但拉群任务表单目前没有这一项，因此恒为留空（不下发）。
     *
     * <p>不要把它接到 {@code link_permission_mode}：那一列是「谁能拿群邀请链接」，与「谁能加人」
     * 不是一回事，接上等于替运营下发一个他没表达过的权限变更。表单补上该项后在这里接上即可，
     * 协议侧契约不用改。</p>
     */
    private static Boolean addMembersAllowed() {
        return null;
    }

    /**
     * 入群审批：契约保留该字段，但拉群任务表单目前没有这一项。
     *
     * <p>{@code pull_task_standard_group_setting} 没有对应列，取不到值，因此恒为留空（不下发）。
     * 表单补上该项后在这里接上即可，协议侧契约不用改。</p>
     */
    private static Boolean joinApprovalEnabled() {
        return null;
    }

    /** 限时消息：各档折算成秒；关闭档为 0，「不操作」留空。 */
    private static Integer ephemeralDurationSeconds(Integer disappearingMessageMode) {
        if (disappearingMessageMode == null) {
            return null;
        }
        return switch (PullTaskDisappearingMessageMode.fromCode(disappearingMessageMode)) {
            case UNCHANGED -> null;
            case ONE_DAY -> EPHEMERAL_ONE_DAY_SECONDS;
            case SEVEN_DAYS -> EPHEMERAL_SEVEN_DAYS_SECONDS;
            case NINETY_DAYS -> EPHEMERAL_NINETY_DAYS_SECONDS;
            case OFF -> EPHEMERAL_OFF_SECONDS;
        };
    }

    /** 总开关关闭时表单里填过的值一律不算数，不允许下发。 */
    private static void requireEnabled(PullTaskStandardGroupSetting setting) {
        if (setting == null
                || setting.getGroupSettingEnabled() == null
                || setting.getGroupSettingEnabled() != SETTING_ENABLED) {
            throw validation("拉群群信息设置总开关未开启，不下发群资料命令");
        }
    }

    private ProtocolPullTaskParticipantActionReference parse(JsonNode payload) {
        try {
            return objectMapper.treeToValue(
                    payload, ProtocolPullTaskParticipantActionReference.class);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw validation("拉群群信息设置命令引用 payload 非法");
        }
    }

    private static void validateReference(
            ProtocolCommandOutbox row, ProtocolPullTaskParticipantActionReference reference) {
        if (reference == null
                || reference.tenantId() == null
                || reference.pullTaskId() == null
                || reference.groupExecutionId() == null
                || reference.actionId() == null
                || !ProtocolPullTaskGroupProfileCommandRequest.SOURCE.equals(reference.source())
                || !Objects.equals(reference.tenantId(), row.getTenantId())
                || !Objects.equals(reference.actionId(), row.getAggregateId())) {
            throw validation("拉群群信息设置命令引用字段非法");
        }
    }

    /** 只认「群信息设置」动作类型，别的动作不属于本补全器。 */
    private static boolean validAction(
            PullTaskAccountAction action,
            ProtocolCommandOutbox row,
            ProtocolPullTaskParticipantActionReference reference) {
        return action != null
                && Objects.equals(action.getId(), reference.actionId())
                && Objects.equals(action.getTenantId(), reference.tenantId())
                && Objects.equals(action.getTaskId(), reference.pullTaskId())
                && Objects.equals(action.getGroupExecutionId(), reference.groupExecutionId())
                && Objects.equals(action.getCommandId(), row.getCommandId())
                && action.getAttemptNo() != null
                && action.getActionType() != null
                && action.getActionType()
                        == PullTaskAccountActionType.APPLY_GROUP_SETTINGS.code();
    }

    private static boolean validManager(
            PullTaskGroupAccount manager, ProtocolPullTaskParticipantActionReference reference) {
        return manager != null
                && Objects.equals(manager.getGroupExecutionId(), reference.groupExecutionId())
                && manager.getAccountId() != null
                && hasText(manager.getAccountPhone());
    }

    private static boolean validExecution(
            PullTaskGroupExecution execution,
            ProtocolPullTaskParticipantActionReference reference) {
        return execution != null
                && Objects.equals(execution.getId(), reference.groupExecutionId())
                && Objects.equals(execution.getTaskId(), reference.pullTaskId())
                && hasText(execution.getGroupJid());
    }

    private static ProtocolBackend backend(ProtocolCommandOutbox row) {
        try {
            return ProtocolBackend.valueOf(row.getProtocolBackend());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw validation("拉群群信息设置命令协议后端非法");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }
}
