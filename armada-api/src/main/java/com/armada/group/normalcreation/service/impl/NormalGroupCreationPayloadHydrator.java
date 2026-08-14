package com.armada.group.normalcreation.service.impl;

import com.armada.group.normalcreation.mapper.NormalGroupCreationMapper;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.SecondaryAdminWork;
import com.armada.group.normalcreation.support.NormalGroupCreationParticipantEligibility;
import com.armada.group.normalcreation.support.NormalGroupCreationSubject;
import com.armada.platform.protocol.model.command.ProtocolNormalGroupCreationCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolNormalGroupCreationReference;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.service.ProtocolCommandPayloadHydrator;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 从新建普群冻结任务、计划群和成员快照补全协议可执行 payload。 */
@Component
public class NormalGroupCreationPayloadHydrator implements ProtocolCommandPayloadHydrator {

    private static final String COMMAND_TYPE = "group.normal_creation.requested";
    private static final String AGGREGATE_TYPE = "NORMAL_GROUP_CREATION_ITEM";

    private final NormalGroupCreationMapper mapper;
    private final ObjectMapper objectMapper;

    public NormalGroupCreationPayloadHydrator(
            NormalGroupCreationMapper mapper,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ProtocolCommandOutbox row) {
        return row != null
                && COMMAND_TYPE.equals(row.getCommandType())
                && AGGREGATE_TYPE.equals(row.getAggregateType());
    }

    @Override
    public JsonNode hydrate(ProtocolCommandOutbox row, JsonNode referencePayload) {
        ProtocolNormalGroupCreationReference reference = parse(referencePayload);
        validateReference(row, reference);
        Long previousTenant = TenantContext.get();
        TenantContext.set(reference.tenantId());
        try {
            ItemWork item = mapper.selectItemWork(reference.itemId());
            if (item == null
                    || !Objects.equals(item.tenantId(), reference.tenantId())
                    || !Objects.equals(item.taskId(), reference.taskId())) {
                throw validation("新建普群命令关联计划群不存在");
            }
            List<MemberWork> members = mapper.selectMemberWorks(item.id());
            List<SecondaryAdminWork> secondaryAdmins = mapper.selectSecondaryAdminWorks(item.id());
            ContactWork contactWork = contactWork(
                    reference, members, secondaryAdmins, item.creatorWsPhone());
            validateCommandBinding(row, reference, item, contactWork);
            LinkedHashSet<String> participantPhones = new LinkedHashSet<>();
            secondaryAdmins.stream()
                    .filter(NormalGroupCreationParticipantEligibility
                            ::secondaryAdminHasMutualCreatorContact)
                    .map(SecondaryAdminWork::secondaryAdminWsPhone)
                    .forEach(participantPhones::add);
            members.stream()
                    .filter(NormalGroupCreationParticipantEligibility
                            ::memberHasMutualCreatorContact)
                    .map(MemberWork::memberWsPhone)
                    .forEach(participantPhones::add);
            List<String> participants = List.copyOf(participantPhones);
            String promoteCandidate = participants.isEmpty() ? null : participants.get(0);
            return objectMapper.valueToTree(new WirePayload(
                    reference.tenantId(), reference.taskId(), reference.itemId(),
                    reference.memberId(), protocolDirection(reference.direction()),
                    actorAccountId(item, contactWork),
                    row.getProtocolAccountId(), actorPhone(item, contactWork),
                    row.getProtocolBackend(), reference.action(), 1, reference.source(),
                    contactWork == null ? null : contactWork.contactPhone(),
                    contactWork == null ? null : contactWork.contactPhone(), item.groupSubject(),
                    NormalGroupCreationSubject.isAutomatic(item.groupNameTemplate()),
                    participants, item.groupJid(),
                    Boolean.TRUE.equals(item.sendMessagesAllowed()),
                    Boolean.TRUE.equals(item.editGroupSettingsAllowed()),
                    Boolean.TRUE.equals(item.addMembersAllowed()),
                    Boolean.TRUE.equals(item.joinApprovalEnabled()),
                    item.ephemeralDurationSeconds(), promoteCandidate));
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private ProtocolNormalGroupCreationReference parse(JsonNode payload) {
        try {
            return objectMapper.treeToValue(payload, ProtocolNormalGroupCreationReference.class);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw validation("新建普群命令引用 payload 非法");
        }
    }

    private static void validateReference(
            ProtocolCommandOutbox row,
            ProtocolNormalGroupCreationReference reference) {
        if (reference == null
                || !positive(reference.tenantId())
                || !positive(reference.taskId())
                || !positive(reference.itemId())
                || !reference.tenantId().equals(row.getTenantId())
                || !reference.itemId().equals(row.getAggregateId())
                || !explicitBackend(row.getProtocolBackend())
                || !ProtocolNormalGroupCreationCommandRequest.SOURCE.equals(reference.source())) {
            throw validation("新建普群命令引用与 Outbox 不一致");
        }
    }

    private static boolean explicitBackend(String value) {
        return "WEB".equals(value) || "ANDROID".equals(value);
    }

    /**
     * 内部四个次管理员联系人方向映射为协议层既有的两个方向值。
     * 协议执行只使用 actor/contact 保存联系人，并原样回传 direction，无需升级协议仓。
     */
    private static String protocolDirection(String internalDirection) {
        if (internalDirection == null) {
            return null;
        }
        return switch (internalDirection) {
            case "CREATOR_SAVE_MEMBER", "CREATOR_SAVE_SECONDARY", "SECONDARY_SAVE_ANCHOR" ->
                    "CREATOR_SAVE_MEMBER";
            case "MEMBER_SAVE_CREATOR", "SECONDARY_SAVE_CREATOR", "ANCHOR_SAVE_SECONDARY" ->
                    "MEMBER_SAVE_CREATOR";
            default -> throw validation("新建普群联系人准备方向非法");
        };
    }

    private static ContactWork contactWork(
            ProtocolNormalGroupCreationReference reference,
            List<MemberWork> members,
            List<SecondaryAdminWork> secondaryAdmins,
            String creatorPhone) {
        if (!"CONTACT_PREPARE".equals(reference.action())) {
            return null;
        }
        if (List.of("CREATOR_SAVE_MEMBER", "MEMBER_SAVE_CREATOR")
                .contains(reference.direction())) {
            MemberWork member = members.stream()
                    .filter(row -> Objects.equals(row.id(), reference.memberId()))
                    .findFirst()
                    .orElseThrow(() -> validation("新建普群联系人命令关联普通成员不存在"));
            return "CREATOR_SAVE_MEMBER".equals(reference.direction())
                    ? new ContactWork(member.creatorSaveCommandId(), null, null,
                    member.memberWsPhone())
                    : new ContactWork(member.memberSaveCommandId(), member.memberAccountId(),
                    member.memberWsPhone(), creatorPhone);
        }
        SecondaryAdminWork secondary = secondaryAdmins.stream()
                .filter(row -> Objects.equals(row.id(), reference.memberId()))
                .findFirst()
                .orElseThrow(() -> validation("新建普群联系人命令关联次管理员不存在"));
        return switch (reference.direction()) {
            case "CREATOR_SAVE_SECONDARY" -> new ContactWork(
                    secondary.creatorSaveCommandId(), null, null,
                    secondary.secondaryAdminWsPhone());
            case "SECONDARY_SAVE_CREATOR" -> new ContactWork(
                    secondary.secondarySaveCreatorCommandId(),
                    secondary.secondaryAdminAccountId(), secondary.secondaryAdminWsPhone(),
                    creatorPhone);
            case "SECONDARY_SAVE_ANCHOR" -> new ContactWork(
                    secondary.secondarySaveAnchorCommandId(),
                    secondary.secondaryAdminAccountId(), secondary.secondaryAdminWsPhone(),
                    secondary.anchorMemberWsPhone());
            case "ANCHOR_SAVE_SECONDARY" -> new ContactWork(
                    secondary.anchorSaveSecondaryCommandId(), secondary.anchorMemberAccountId(),
                    secondary.anchorMemberWsPhone(), secondary.secondaryAdminWsPhone());
            default -> throw validation("新建普群联系人准备方向非法");
        };
    }

    private void validateCommandBinding(
            ProtocolCommandOutbox row,
            ProtocolNormalGroupCreationReference reference,
            ItemWork item,
            ContactWork contactWork) {
        String bound = switch (reference.action()) {
            case "CONTACT_PREPARE" -> contactWork.commandId();
            case "GROUP_CREATE" -> item.createCommandId();
            case "GROUP_SETTINGS_APPLY" -> item.settingsCommandId();
            case "GROUP_LEAVE" -> item.leaveCommandId();
            default -> throw validation("新建普群 action 非法");
        };
        if (!Objects.equals(bound, row.getCommandId())) {
            throw validation("新建普群命令与当前阶段 commandId 不一致");
        }
    }

    private static String actorPhone(ItemWork item, ContactWork contactWork) {
        return contactWork != null && contactWork.actorPhone() != null
                ? contactWork.actorPhone() : item.creatorWsPhone();
    }

    private static Long actorAccountId(ItemWork item, ContactWork contactWork) {
        return contactWork != null && contactWork.actorAccountId() != null
                ? contactWork.actorAccountId() : item.creatorAccountId();
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }

    private static void restoreTenant(Long tenantId) {
        if (tenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(tenantId);
        }
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    private record WirePayload(
            Long tenantId,
            Long taskId,
            Long itemId,
            Long memberId,
            String direction,
            Long accountId,
            String protocolAccountId,
            String wsPhone,
            String protocolBackend,
            String action,
            int attemptNo,
            String source,
            String contact,
            String name,
            String subject,
            boolean autoGeneratedSubject,
            List<String> participants,
            String groupJid,
            boolean sendMessagesAllowed,
            boolean editGroupSettingsAllowed,
            boolean addMembersAllowed,
            boolean joinApprovalEnabled,
            int ephemeralDurationSeconds,
            String promoteCandidate
    ) {
    }

    /** 单个联系人准备方向的冻结执行账号、命令和目标号码。 */
    private record ContactWork(
            String commandId,
            Long actorAccountId,
            String actorPhone,
            String contactPhone) {
    }
}
