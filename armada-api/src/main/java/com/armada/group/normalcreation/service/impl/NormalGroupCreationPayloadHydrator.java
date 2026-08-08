package com.armada.group.normalcreation.service.impl;

import com.armada.group.normalcreation.mapper.NormalGroupCreationMapper;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberWork;
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
            MemberWork member = contactMember(reference, members);
            validateCommandBinding(row, reference, item, member);
            String contact = null;
            String name = null;
            if (member != null && "CREATOR_SAVE_MEMBER".equals(reference.direction())) {
                contact = member.memberWsPhone();
                name = member.memberWsPhone();
            } else if (member != null) {
                contact = item.creatorWsPhone();
                name = item.creatorWsPhone();
            }
            List<String> participants = members.stream().map(MemberWork::memberWsPhone).toList();
            String promoteCandidate = participants.isEmpty() ? null : participants.get(0);
            return objectMapper.valueToTree(new WirePayload(
                    reference.tenantId(), reference.taskId(), reference.itemId(),
                    reference.memberId(), reference.direction(), actorAccountId(reference, item, member),
                    row.getProtocolAccountId(), actorPhone(reference, item, member),
                    row.getProtocolBackend(), reference.action(), 1, reference.source(),
                    contact, name, item.groupSubject(),
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

    private static MemberWork contactMember(
            ProtocolNormalGroupCreationReference reference,
            List<MemberWork> members) {
        if (!"CONTACT_PREPARE".equals(reference.action())) {
            return null;
        }
        return members.stream()
                .filter(row -> Objects.equals(row.id(), reference.memberId()))
                .findFirst()
                .orElseThrow(() -> validation("新建普群联系人命令关联成员不存在"));
    }

    private static void validateCommandBinding(
            ProtocolCommandOutbox row,
            ProtocolNormalGroupCreationReference reference,
            ItemWork item,
            MemberWork member) {
        String bound = switch (reference.action()) {
            case "CONTACT_PREPARE" -> "CREATOR_SAVE_MEMBER".equals(reference.direction())
                    ? member.creatorSaveCommandId() : member.memberSaveCommandId();
            case "GROUP_CREATE" -> item.createCommandId();
            case "GROUP_SETTINGS_APPLY" -> item.settingsCommandId();
            case "GROUP_LEAVE" -> item.leaveCommandId();
            default -> throw validation("新建普群 action 非法");
        };
        if (!Objects.equals(bound, row.getCommandId())) {
            throw validation("新建普群命令与当前阶段 commandId 不一致");
        }
    }

    private static String actorPhone(
            ProtocolNormalGroupCreationReference reference,
            ItemWork item,
            MemberWork member) {
        return "MEMBER_SAVE_CREATOR".equals(reference.direction())
                ? member.memberWsPhone() : item.creatorWsPhone();
    }

    private static Long actorAccountId(
            ProtocolNormalGroupCreationReference reference,
            ItemWork item,
            MemberWork member) {
        return "MEMBER_SAVE_CREATOR".equals(reference.direction())
                ? member.memberAccountId() : item.creatorAccountId();
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
}
